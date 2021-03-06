/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.qualityprofile;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.profiles.ProfileExporter;
import org.sonar.api.profiles.ProfileImporter;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.rules.ActiveRuleParam;
import org.sonar.api.rules.Rule;
import org.sonar.api.rules.RuleFinder;
import org.sonar.api.rules.RulePriority;
import org.sonar.api.server.ServerSide;
import org.sonar.api.utils.ValidationMessages;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.qualityprofile.ActiveRuleDto;
import org.sonar.db.qualityprofile.ActiveRuleParamDto;
import org.sonar.db.qualityprofile.QualityProfileDto;
import org.sonar.server.exceptions.BadRequestException;
import org.sonar.server.exceptions.NotFoundException;

@ServerSide
public class QProfileExporters {

  private final DbClient dbClient;
  private final RuleFinder ruleFinder;
  private final RuleActivator ruleActivator;
  private final ProfileExporter[] exporters;
  private final ProfileImporter[] importers;

  public QProfileExporters(DbClient dbClient, RuleFinder ruleFinder, RuleActivator ruleActivator, ProfileExporter[] exporters, ProfileImporter[] importers) {
    this.dbClient = dbClient;
    this.ruleFinder = ruleFinder;
    this.ruleActivator = ruleActivator;
    this.exporters = exporters;
    this.importers = importers;
  }

  /**
   * Used by Pico if no {@link ProfileImporter} is found
   */
  public QProfileExporters(DbClient dbClient, RuleFinder ruleFinder, RuleActivator ruleActivator, ProfileExporter[] exporters) {
    this(dbClient, ruleFinder, ruleActivator, exporters, new ProfileImporter[0]);
  }

  /**
   * Used by Pico if no {@link ProfileExporter} is found
   */
  public QProfileExporters(DbClient dbClient, RuleFinder ruleFinder, RuleActivator ruleActivator, ProfileImporter[] importers) {
    this(dbClient, ruleFinder, ruleActivator, new ProfileExporter[0], importers);
  }

  /**
   * Used by Pico if no {@link ProfileImporter} nor {@link ProfileExporter} is found
   */
  public QProfileExporters(DbClient dbClient, RuleFinder ruleFinder, RuleActivator ruleActivator) {
    this(dbClient, ruleFinder, ruleActivator, new ProfileExporter[0], new ProfileImporter[0]);
  }

  public List<ProfileExporter> exportersForLanguage(String language) {
    List<ProfileExporter> result = new ArrayList<>();
    for (ProfileExporter exporter : exporters) {
      if (exporter.getSupportedLanguages() == null || exporter.getSupportedLanguages().length == 0 || ArrayUtils.contains(exporter.getSupportedLanguages(), language)) {
        result.add(exporter);
      }
    }
    return result;
  }

  public String mimeType(String exporterKey) {
    ProfileExporter exporter = findExporter(exporterKey);
    return exporter.getMimeType();
  }

  public void export(QualityProfileDto profile, String exporterKey, Writer writer) {
    ProfileExporter exporter = findExporter(exporterKey);
    exporter.exportProfile(wrap(profile), writer);
  }

  private RulesProfile wrap(QualityProfileDto profile) {
    DbSession dbSession = dbClient.openSession(false);
    RulesProfile target = new RulesProfile(profile.getName(), profile.getLanguage());
    try {
      List<ActiveRuleDto> activeRuleDtos = dbClient.activeRuleDao().selectByProfileKey(dbSession, profile.getKey());
      List<ActiveRuleParamDto> activeRuleParamDtos = dbClient.activeRuleDao().selectParamsByActiveRuleIds(dbSession, Lists.transform(activeRuleDtos, ActiveRuleDto::getId));
      ListMultimap<Integer, ActiveRuleParamDto> activeRuleParamsByActiveRuleId = FluentIterable.from(activeRuleParamDtos).index(ActiveRuleParamDto::getActiveRuleId);

      for (ActiveRuleDto activeRule : activeRuleDtos) {
        // TODO all rules should be loaded by using one query with all active rule keys as parameter
        Rule rule = ruleFinder.findByKey(activeRule.getKey().ruleKey());
        org.sonar.api.rules.ActiveRule wrappedActiveRule = target.activateRule(rule, RulePriority.valueOf(activeRule.getSeverityString()));
        List<ActiveRuleParamDto> paramDtos = activeRuleParamsByActiveRuleId.get(activeRule.getId());
        for (ActiveRuleParamDto activeRuleParamDto : paramDtos) {
          wrappedActiveRule.setParameter(activeRuleParamDto.getKey(), activeRuleParamDto.getValue());
        }
      }
    } finally {
      dbClient.closeSession(dbSession);
    }
    return target;
  }

  private ProfileExporter findExporter(String exporterKey) {
    for (ProfileExporter e : exporters) {
      if (exporterKey.equals(e.getKey())) {
        return e;
      }
    }
    throw new NotFoundException("Unknown quality profile exporter: " + exporterKey);
  }

  public QProfileResult importXml(QualityProfileDto profileDto, String importerKey, InputStream xml, DbSession dbSession) {
    return importXml(profileDto, importerKey, new InputStreamReader(xml, StandardCharsets.UTF_8), dbSession);
  }

  private QProfileResult importXml(QualityProfileDto profileDto, String importerKey, Reader xml, DbSession dbSession) {
    QProfileResult result = new QProfileResult();
    ValidationMessages messages = ValidationMessages.create();
    ProfileImporter importer = getProfileImporter(importerKey);
    RulesProfile rulesProfile = importer.importProfile(xml, messages);
    List<ActiveRuleChange> changes = importProfile(profileDto, rulesProfile, dbSession);
    result.addChanges(changes);
    processValidationMessages(messages, result);
    return result;
  }

  private List<ActiveRuleChange> importProfile(QualityProfileDto profileDto, RulesProfile rulesProfile, DbSession dbSession) {
    List<ActiveRuleChange> changes = new ArrayList<>();
    for (org.sonar.api.rules.ActiveRule activeRule : rulesProfile.getActiveRules()) {
      changes.addAll(ruleActivator.activate(dbSession, toRuleActivation(activeRule), profileDto));
    }
    return changes;
  }

  private ProfileImporter getProfileImporter(String importerKey) {
    for (ProfileImporter importer : importers) {
      if (StringUtils.equals(importerKey, importer.getKey())) {
        return importer;
      }
    }
    throw new BadRequestException("No such importer : " + importerKey);
  }

  private static void processValidationMessages(ValidationMessages messages, QProfileResult result) {
    if (!messages.getErrors().isEmpty()) {
      throw BadRequestException.create(messages.getErrors());
    }
    result.addWarnings(messages.getWarnings());
    result.addInfos(messages.getInfos());
  }

  private static RuleActivation toRuleActivation(org.sonar.api.rules.ActiveRule activeRule) {
    RuleActivation ruleActivation = new RuleActivation(activeRule.getRule().ruleKey());
    ruleActivation.setSeverity(activeRule.getSeverity().name());
    for (ActiveRuleParam activeRuleParam : activeRule.getActiveRuleParams()) {
      ruleActivation.setParameter(activeRuleParam.getKey(), activeRuleParam.getValue());
    }
    return ruleActivation;
  }

}

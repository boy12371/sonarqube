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
package org.sonar.server.organization;

import java.util.Optional;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.api.web.UserRole;
import org.sonar.db.DbSession;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.user.UserDto;

import static java.util.Objects.requireNonNull;

public interface OrganizationCreation {
  String OWNERS_GROUP_NAME = "Owners";
  String OWNERS_GROUP_DESCRIPTION_PATTERN = "Owners of organization %s";
  String PERM_TEMPLATE_DESCRIPTION_PATTERN = "Default permission template of organization %s";

  /**
   * Create a new non guarded organization with the specified properties and of which the specified user will assign
   * Administer Organization permission.
   * <p>
   * This method does several operations at once:
   * <ol>
   *   <li>create an ungarded organization with the specified details</li>
   *   <li>create a group called {@link #OWNERS_GROUP_NAME Owners} with Administer Organization permission</li>
   *   <li>make the specified user a member of this group</li>
   *   <li>create a default template for the organization (which name and description will follow patterns
   *       {@link #OWNERS_GROUP_NAME} and {@link #OWNERS_GROUP_DESCRIPTION_PATTERN} based on the organization name)</li>
   *   <li>this group defines the specified permissions (which effectively makes projects public):
   *     <ul>
   *       <li>group {@link #OWNERS_GROUP_NAME Owners} : {@link UserRole#ADMIN ADMIN}</li>
   *       <li>group {@link #OWNERS_GROUP_NAME Owners} : {@link UserRole#ISSUE_ADMIN ISSUE_ADMIN}</li>
   *       <li>any one : {@link UserRole#USER USER}</li>
   *       <li>any one : {@link UserRole#CODEVIEWER CODEVIEWER}</li>
   *     </ul>
   *   </li>
   * </ol>
   * </p>
   *
   * @return the created organization
   *
   * @throws KeyConflictException if an organization with the specified key already exists
   * @throws IllegalArgumentException if any field of {@code newOrganization} is invalid according to {@link OrganizationValidation}
   */
  OrganizationDto create(DbSession dbSession, int createUserId, NewOrganization newOrganization) throws KeyConflictException;

  /**
   * Create a new guarded organization which details are based on the login of the specified User.
   * <p>
   * This method create the organization and its associated elements in exactly the same was as
   * {@link #create(DbSession, long, NewOrganization)} with the organization's details computed from the
   * user's login:
   * <ul>
   *   <li>key: generated from the user's login</li>
   *   <li>name: the user's login</li>
   *   <li>description, url and avatar: null</li>
   * </ul>
   * </p>
   *
   * @return the created organization or empty if feature is disabled
   *
   * @throws IllegalArgumentException if any field of {@code newOrganization} is invalid according to {@link OrganizationValidation}
   * @throws IllegalStateException if an organization with the key generated from the login already exists
   */
  Optional<OrganizationDto> createForUser(DbSession dbSession, UserDto newUser);

  final class KeyConflictException extends Exception {
    KeyConflictException(String message) {
      super(message);
    }
  }

  final class NewOrganization {
    private final String key;
    private final String name;
    @CheckForNull
    private final String description;
    @CheckForNull
    private final String url;
    @CheckForNull
    private final String avatar;

    private NewOrganization(Builder builder) {
      this.key = builder.key;
      this.name = builder.name;
      this.description = builder.description;
      this.url = builder.url;
      this.avatar = builder.avatarUrl;
    }

    public String getKey() {
      return key;
    }

    public String getName() {
      return name;
    }

    @CheckForNull
    public String getDescription() {
      return description;
    }

    @CheckForNull
    public String getUrl() {
      return url;
    }

    @CheckForNull
    public String getAvatar() {
      return avatar;
    }

    public static NewOrganization.Builder newOrganizationBuilder() {
      return new Builder();
    }

    public static final class Builder {
      private String key;
      private String name;
      private String description;
      private String url;
      private String avatarUrl;

      private Builder() {
        // use factory method
      }

      public Builder setKey(String key) {
        this.key = requireNonNull(key, "key can't be null");
        return this;
      }

      public Builder setName(String name) {
        this.name = requireNonNull(name, "name can't be null");
        return this;
      }

      public Builder setDescription(@Nullable String description) {
        this.description = description;
        return this;
      }

      public Builder setUrl(@Nullable String url) {
        this.url = url;
        return this;
      }

      public Builder setAvatarUrl(@Nullable String avatarUrl) {
        this.avatarUrl = avatarUrl;
        return this;
      }

      public NewOrganization build() {
        requireNonNull(key, "key can't be null");
        requireNonNull(name, "name can't be null");
        return new NewOrganization(this);
      }
    }
  }

}

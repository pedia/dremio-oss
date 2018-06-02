/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.dac.resource;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import com.dremio.dac.annotations.RestResource;
import com.dremio.dac.annotations.Secured;
import com.dremio.dac.proto.model.acceleration.SystemSettingsApiDescriptor;
import com.dremio.dac.service.reflection.ReflectionServiceHelper;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.server.options.OptionValue;
import com.dremio.exec.server.options.SystemOptionManager;
import com.dremio.service.reflection.ReflectionOptions;
import com.google.common.base.Preconditions;

/**
 * API for setting low-level development options. Not meant to be a permanent API.
 */
@RestResource
@Secured
@RolesAllowed({"admin", "user"})
@Path("/development_options")
public class DevelopmentOptionsResource {
  private ReflectionServiceHelper reflectionServiceHelper;
  private SabotContext context;

  @Inject
  public DevelopmentOptionsResource(ReflectionServiceHelper reflectionServiceHelper, SabotContext context) {
    this.reflectionServiceHelper = reflectionServiceHelper;
    this.context = context;
  }

  @GET
  @Path("/acceleration/enabled")
  @Produces(MediaType.APPLICATION_JSON)
  public String isGlobalAccelerationEnabled() {
    return Boolean.toString(reflectionServiceHelper.isSubstitutionEnabled());
  }

  @PUT
  @Path("/acceleration/enabled")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public String setAccelerationEnabled(/* Body */String body) {
    boolean enabled = Boolean.valueOf(body);
    reflectionServiceHelper.setSubstitutionEnabled(enabled);
    return body;
  }

  @POST
  @Path("/acceleration/clearall")
  public void clearMaterializations() {
    reflectionServiceHelper.clearAllReflections();
  }

  @GET
  @Path("/acceleration/settings")
  @Produces(MediaType.APPLICATION_JSON)
  public SystemSettingsApiDescriptor getSystemSettings() {
    SystemOptionManager optionManager = context.getOptionManager();
    return new SystemSettingsApiDescriptor()
      .setLimit((int) optionManager.getOption(ReflectionOptions.MAX_AUTOMATIC_REFLECTIONS))
      .setAccelerateAggregation(optionManager.getOption(ReflectionOptions.ENABLE_AUTOMATIC_AGG_REFLECTIONS))
      .setAccelerateRaw(optionManager.getOption(ReflectionOptions.ENABLE_AUTOMATIC_RAW_REFLECTIONS))
      .setLayoutRefreshMaxAttempts((int) optionManager.getOption(ExecConstants.LAYOUT_REFRESH_MAX_ATTEMPTS));
  }


  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/acceleration/settings")
  public void saveSystemSettings(final SystemSettingsApiDescriptor descriptor) {
    Preconditions.checkArgument(descriptor.getLimit() != null, "limit is required");
    Preconditions.checkArgument(descriptor.getLimit() > 0, "limit must be positive");
    Preconditions.checkArgument(descriptor.getAccelerateAggregation() != null, "accelerateAggregation is required");
    Preconditions.checkArgument(descriptor.getAccelerateRaw() != null, "accelerateRaw is required");

    SystemOptionManager optionManager = context.getOptionManager();

    optionManager.setOption(OptionValue.createLong(OptionValue.OptionType.SYSTEM, ReflectionOptions.MAX_AUTOMATIC_REFLECTIONS.getOptionName(), descriptor.getLimit()));
    optionManager.setOption(OptionValue.createBoolean(OptionValue.OptionType.SYSTEM, ReflectionOptions.ENABLE_AUTOMATIC_AGG_REFLECTIONS.getOptionName(), descriptor.getAccelerateAggregation()));
    optionManager.setOption(OptionValue.createBoolean(OptionValue.OptionType.SYSTEM, ReflectionOptions.ENABLE_AUTOMATIC_RAW_REFLECTIONS.getOptionName(), descriptor.getAccelerateRaw()));
    if (descriptor.getLayoutRefreshMaxAttempts() != null) {
      optionManager.setOption(OptionValue.createLong(OptionValue.OptionType.SYSTEM, ExecConstants.LAYOUT_REFRESH_MAX_ATTEMPTS.getOptionName(), descriptor.getLayoutRefreshMaxAttempts()));
    }
  }
}
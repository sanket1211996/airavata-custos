/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.custos.tenant.management.tasks;

import org.apache.custos.credential.store.client.CredentialStoreServiceClient;
import org.apache.custos.credential.store.service.CredentialMetadata;
import org.apache.custos.credential.store.service.GetCredentialRequest;
import org.apache.custos.credential.store.service.Type;
import org.apache.custos.federated.authentication.client.FederatedAuthenticationClient;
import org.apache.custos.federated.authentication.service.ClientMetadata;
import org.apache.custos.federated.authentication.service.RegisterClientResponse;
import org.apache.custos.iam.admin.client.IamAdminServiceClient;
import org.apache.custos.iam.service.ConfigureFederateIDPRequest;
import org.apache.custos.iam.service.FederatedIDPs;
import org.apache.custos.iam.service.SetUpTenantRequest;
import org.apache.custos.iam.service.SetUpTenantResponse;
import org.apache.custos.integration.core.ServiceException;
import org.apache.custos.integration.core.ServiceTaskImpl;
import org.apache.custos.sharing.client.SharingClient;
import org.apache.custos.sharing.service.EntityType;
import org.apache.custos.sharing.service.EntityTypeRequest;
import org.apache.custos.sharing.service.PermissionType;
import org.apache.custos.sharing.service.PermissionTypeRequest;
import org.apache.custos.tenant.management.utils.Constants;
import org.apache.custos.tenant.profile.client.async.TenantProfileClient;
import org.apache.custos.tenant.profile.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class TenantActivationTask<T, U> extends ServiceTaskImpl<T, U> {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantActivationTask.class);


    @Autowired
    private IamAdminServiceClient iamAdminServiceClient;

    @Autowired
    private FederatedAuthenticationClient federatedAuthenticationClient;

    @Autowired
    private CredentialStoreServiceClient credentialStoreServiceClient;

    @Autowired
    private TenantProfileClient profileClient;

    @Autowired
    private SharingClient sharingClient;


    @Override
    public void invokeService(T data) {
        try {
            if (data instanceof UpdateStatusResponse) {
                long tenantId = ((UpdateStatusResponse) data).getTenantId();
                LOGGER.debug("Invoking tenant activation task for tenant " + tenantId);

                GetTenantRequest tenantRequest = GetTenantRequest
                        .newBuilder()
                        .setTenantId(tenantId)
                        .build();

                GetTenantResponse tenantRes = profileClient.getTenant(tenantRequest);

                Tenant tenant = tenantRes.getTenant();

                if (tenant != null) {

                    GetCredentialRequest request = GetCredentialRequest
                            .newBuilder()
                            .setId(tenant.getAdminUsername())
                            .setOwnerId(tenantId)
                            .setType(Type.INDIVIDUAL)
                            .build();
                    CredentialMetadata metadata = credentialStoreServiceClient.getCredential(request);

                    if (metadata != null && metadata.getSecret() != null) {

                        Tenant newTenant = tenant.toBuilder().setAdminPassword(metadata.getSecret()).build();


                        GetCredentialRequest iamClientReques = GetCredentialRequest
                                .newBuilder()
                                .setOwnerId(tenantId)
                                .setType(Type.IAM)
                                .build();

                        CredentialMetadata iamMetadata = credentialStoreServiceClient.getCredential(iamClientReques);

                        UpdateStatusResponse response = null;
                        if (iamMetadata == null || iamMetadata.getId() == null || iamMetadata.getId().equals("")) {
                            response = this.activateTenant(newTenant, Constants.SYSTEM, false);
                        } else {
                            response = this.activateTenant(newTenant, Constants.SYSTEM, true);
                        }

                        invokeNextTask((U) response);

                    } else {
                        String msg = "Admin password not found  for admin  " + tenant.getAdminUsername();
                        LOGGER.error(msg);
                        getServiceCallback().onError(new ServiceException(msg, null, null));
                    }
                } else {
                    String msg = "Tenant not found  for Id  " + tenantId;
                    LOGGER.error(msg);
                    getServiceCallback().onError(new ServiceException(msg, null, null));
                }
            } else {
                String msg = "Invalid payload type ";
                LOGGER.error(msg);
                getServiceCallback().onError(new ServiceException(msg, null, null));
            }
        } catch (Exception ex) {
            String msg = "Error occurred  " + ex.getCause();
            LOGGER.error(msg, ex);
            getServiceCallback().onError(new ServiceException(msg, ex.getCause(), null));
        }
    }


    public UpdateStatusResponse activateTenant(Tenant tenant, String performedBy, boolean update) {


        GetCredentialRequest getCreRe = GetCredentialRequest.newBuilder().
                setOwnerId(tenant.getTenantId())
                .setType(Type.CUSTOS)
                .build();

        CredentialMetadata metadata = credentialStoreServiceClient.getCredential(getCreRe);

        SetUpTenantRequest setUpTenantRequest = SetUpTenantRequest
                .newBuilder()
                .setTenantId(tenant.getTenantId())
                .setTenantName(tenant.getClientName())
                .setAdminFirstname(tenant.getAdminFirstName())
                .setAdminLastname(tenant.getAdminLastName())
                .setAdminEmail(tenant.getAdminEmail())
                .addAllRedirectURIs(tenant.getRedirectUrisList())
                .setAdminPassword(tenant.getAdminPassword())
                .setAdminUsername(tenant.getAdminUsername())
                .setRequesterEmail(tenant.getRequesterEmail())
                .setTenantURL(tenant.getClientUri())
                .setCustosClientId(metadata.getId())
                .build();

        SetUpTenantResponse iamResponse = null;
        if (update) {
            iamResponse = iamAdminServiceClient.updateTenant(setUpTenantRequest);
        } else {

            iamResponse = iamAdminServiceClient.setUPTenant(setUpTenantRequest);
        }

        CredentialMetadata credentialMetadata = CredentialMetadata
                .newBuilder()
                .setId(iamResponse.getClientId())
                .setSecret(iamResponse.getClientSecret())
                .setOwnerId(tenant.getTenantId())
                .setType(Type.IAM)
                .build();

        credentialStoreServiceClient.putCredential(credentialMetadata);

        String comment = (tenant.getComment() == null || tenant.getComment().trim().equals("")) ?
                "Created by custos" : tenant.getComment();


        String[] scopes = tenant.getScope() != null ? tenant.getScope().split(" ") : new String[0];

        GetCredentialRequest credentialRequest = GetCredentialRequest.newBuilder()
                .setOwnerId(tenant.getTenantId())
                .setType(Type.CILOGON).build();

        String ciLogonRedirectURI = iamAdminServiceClient.getIamServerURL() +
                "realms" + "/" + tenant.getTenantId() + "/" + "broker" + "/" + "oidc" + "/" + "endpoint";


        List<String> arrayList = new ArrayList<>();
        arrayList.add(ciLogonRedirectURI);

        ClientMetadata.Builder clientMetadataBuilder = ClientMetadata
                .newBuilder()
                .setTenantId(tenant.getTenantId())
                .setTenantName(tenant.getClientName())
                .setTenantURI(tenant.getClientUri())
                .setComment(comment)
                .addAllScope(Arrays.asList(scopes))
                .addAllRedirectURIs(arrayList)
                .addAllContacts(tenant.getContactsList())
                .setPerformedBy(performedBy);


        CredentialMetadata creMeta = credentialStoreServiceClient.
                getCredential(credentialRequest);

        clientMetadataBuilder.setClientId(creMeta.getId());


        if (!update) {
//            RegisterClientResponse registerClientResponse = federatedAuthenticationClient
//                    .addClient(clientMetadataBuilder.build());
//
//
//            CredentialMetadata credentialMetadataCILogon = CredentialMetadata
//                    .newBuilder()
//                    .setId(registerClientResponse.getClientId())
//                    .setSecret(registerClientResponse.getClientSecret())
//                    .setOwnerId(tenant.getTenantId())
//                    .setType(Type.CILOGON)
//                    .build();
//
//            credentialStoreServiceClient.putCredential(credentialMetadataCILogon);
//
//
//            ConfigureFederateIDPRequest request = ConfigureFederateIDPRequest
//                    .newBuilder()
//                    .setTenantId(tenant.getTenantId())
//                    .setClientID(registerClientResponse.getClientId())
//                    .setClientSec(registerClientResponse.getClientSecret())
//                    .setScope(tenant.getScope())
//                    .setRequesterEmail(tenant.getRequesterEmail())
//                    .setType(FederatedIDPs.CILOGON)
//                    .build();
//            iamAdminServiceClient.configureFederatedIDP(request);

            PermissionType permissionType = PermissionType
                    .newBuilder()
                    .setId("OWNER")
                    .setName("OWNER")
                    .setDescription("Owner permission type").build();

            PermissionTypeRequest permissionTypeRequest = PermissionTypeRequest
                    .newBuilder()
                    .setPermissionType(permissionType)
                    .setTenantId(tenant.getTenantId())
                    .build();
            sharingClient.createPermissionType(permissionTypeRequest);

            EntityType entityType = EntityType
                    .newBuilder()
                    .setId("SECRET")
                    .setName("SECRET")
                    .setDescription("Secret entity type").build();

            EntityTypeRequest entityTypeRequest = EntityTypeRequest
                    .newBuilder()
                    .setEntityType(entityType)
                    .setTenantId(tenant.getTenantId())
                    .build();
            sharingClient.createEntityType(entityTypeRequest);


        }

        org.apache.custos.tenant.profile.service.UpdateStatusRequest updateTenantRequest =
                org.apache.custos.tenant.profile.service.UpdateStatusRequest.newBuilder()
                        .setTenantId(tenant.getTenantId())
                        .setStatus(TenantStatus.ACTIVE)
                        .setUpdatedBy(Constants.SYSTEM)
                        .build();
        return profileClient.updateTenantStatus(updateTenantRequest);
    }

}
/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.amazon.aws.partners.saasfactory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CreateStackResponse;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeRulesResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.utils.IoUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RegistrationService implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final static Logger LOGGER = LoggerFactory.getLogger(RegistrationService.class);
    private final static String ONBOARDING_TEMPLATE = "onboard-tenant.template";
    private static final Map<String, String> CORS = Map.of("Access-Control-Allow-Origin", "*");
    private final SsmClient ssm;
    private final CloudFormationClient cfn;
    private final ElasticLoadBalancingV2Client elbv2;
    private final CognitoIdentityProviderClient cognito;
    private final DynamoDbClient ddb;
    private String apiGatewayEndpoint;
    private String workshopBucket;
    private String keyPairName;
    private String vpcId;
    private String applicationServerSecurityGroup;
    private String privateSubnetIds;
    private String codePipelineBucket;
    private String codeDeployApplication;
    private String deploymentGroup;
    private String updateCodeDeployLambdaArn;
    private String albListenerArn;
    private String addDatabaseUserArn;

    public RegistrationService() {

        this.ssm = Utils.sdkClient(SsmClient.builder(), SsmClient.SERVICE_NAME);

        GetParametersResponse ssmBatch1 = this.ssm.getParameters(request -> request
                .names("API_GW", "WORKSHOP_BUCKET", "KEY_PAIR", "VPC", "APP_SG","PRIVATE_SUBNETS")
        );

        for (software.amazon.awssdk.services.ssm.model.Parameter parameter : ssmBatch1.parameters()) {
            switch (parameter.name()) {
                case "API_GW":
                    this.apiGatewayEndpoint = parameter.value();
                    LOGGER.info("Setting env api gateway = " + this.apiGatewayEndpoint);
                    break;
                case "WORKSHOP_BUCKET":
                    this.workshopBucket = parameter.value();
                    LOGGER.info("Setting env workshop bucket = " + this.workshopBucket);
                    break;
                case "KEY_PAIR":
                    this.keyPairName = parameter.value();
                    LOGGER.info("Setting env key pair name = " + this.keyPairName);
                    break;
                case "VPC":
                    this.vpcId = parameter.value();
                    LOGGER.info("Setting env vpc = " + this.vpcId);
                    break;
                case "APP_SG":
                    this.applicationServerSecurityGroup = parameter.value();
                    LOGGER.info("Setting env app server security group = " + this.applicationServerSecurityGroup);
                    break;
                case "PRIVATE_SUBNETS":
                    this.privateSubnetIds = parameter.value();
                    LOGGER.info("Setting env private subnets = " + this.privateSubnetIds);
                    break;
            }
        }

        // Can only query for a max of 10 parameters at a time...
        GetParametersResponse ssmBatch2 = this.ssm.getParameters(request -> request
                .names("PIPELINE_BUCKET", "CODE_DEPLOY", "DEPLOYMENT_GROUP", "CODE_DEPLOY_LAMBDA", "ALB_LISTENER", "RDS_ADD_USER_LAMBDA")
        );
        for (software.amazon.awssdk.services.ssm.model.Parameter parameter : ssmBatch2.parameters()) {
            switch (parameter.name()) {
                case "PIPELINE_BUCKET":
                    this.codePipelineBucket = parameter.value();
                    LOGGER.info("Setting env codepipeline bucket = " + this.codePipelineBucket);
                    break;
                case "CODE_DEPLOY":
                    this.codeDeployApplication = parameter.value();
                    LOGGER.info("Setting env codedeploy application = " + this.codeDeployApplication);
                    break;
                case "DEPLOYMENT_GROUP":
                    this.deploymentGroup = parameter.value();
                    LOGGER.info("Setting env codedeploy deployment group = " + this.deploymentGroup);
                    break;
                case "CODE_DEPLOY_LAMBDA":
                    this.updateCodeDeployLambdaArn = parameter.value();
                    LOGGER.info("Setting env update codedeploy lambda = " + this.updateCodeDeployLambdaArn);
                    break;
                case "ALB_LISTENER":
                    this.albListenerArn = parameter.value();
                    LOGGER.info("Setting env alb listener = " + this.albListenerArn);
                    break;
                case "RDS_ADD_USER_LAMBDA":
                    this.addDatabaseUserArn = parameter.value();
                    LOGGER.info("Setting env add db user = " + this.addDatabaseUserArn);
                    break;
            }
        }

        if (Utils.isEmpty(this.apiGatewayEndpoint) ||
                Utils.isEmpty(this.workshopBucket) ||
                Utils.isEmpty(this.keyPairName) ||
                Utils.isEmpty(this.vpcId) ||
                Utils.isEmpty(this.applicationServerSecurityGroup) ||
                Utils.isEmpty(this.privateSubnetIds) ||
                Utils.isEmpty(this.codePipelineBucket) ||
                Utils.isEmpty(this.deploymentGroup) ||
                Utils.isEmpty(this.updateCodeDeployLambdaArn) ||
                Utils.isEmpty(this.albListenerArn) ||
                Utils.isEmpty(this.addDatabaseUserArn)
        ) {
            throw new RuntimeException("Failed to get all required settings from Parameter Store!");
        }

        this.elbv2 = Utils.sdkClient(ElasticLoadBalancingV2Client.builder(), ElasticLoadBalancingV2Client.SERVICE_NAME);
        this.cfn = Utils.sdkClient(CloudFormationClient.builder(), CloudFormationClient.SERVICE_NAME);
        this.cognito = Utils.sdkClient(CognitoIdentityProviderClient.builder(), CognitoIdentityProviderClient.SERVICE_NAME);
        this.ddb = Utils.sdkClient(DynamoDbClient.builder(), DynamoDbClient.SERVICE_NAME);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        return register(event, context);
    }

    /**
     * 1. Call the TenantService to get an unclaimed RDS cluster from the hot pool
     * 2. Call the TenantService to create a new tenant record
     * 3. Update the RDS cluster to add the new application user and password (todo)
     * 4. Save the database connection properties to parameter store so the app servers
     *    for this tenant can configure themselves at runtime
     * 5. Trigger CloudFormation to run the onboarding stack for this tenant
     * @param event
     * @param context
     * @return
     */
    public APIGatewayProxyResponseEvent register(APIGatewayProxyRequestEvent event, Context context) {
        //logRequestEvent(event);
        if (Utils.warmup(event)) {
            LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(HttpURLConnection.HTTP_OK);
        }

        long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("RegistrationService::register");
        APIGatewayProxyResponseEvent response = null;
        Map<String, String> error = new HashMap<>();

        Registration registration = Utils.fromJson(event.getBody(), Registration.class);
        Tenant tenant = null;
        if (registration != null && !registration.isEmpty()) {
            try {
                // Not thread safe - do not copy this

                // 1. Get an available RDS cluster
//                String availableDatabaseResponse = nextAvailableDatabase();
//                Map<String, String> availableDatabase = MAPPER.readValue(availableDatabaseResponse, Map.class);
                Map<String, String> availableDatabase = nextAvailableDatabase();
                if (availableDatabase == null || availableDatabase.isEmpty()) {
                    throw new RuntimeException("Cannot register new tenant. Hot pool of RDS clusters has been depleted.");
                }
                String tenantDatabase = availableDatabase.get("Endpoint");
                LOGGER.info("RegistrationService::register next available database = " + tenantDatabase);

                // 2. Create the new tenant record, generating the UUID
                tenant = createTenant(registration.getCompany(), registration.getPlan(), tenantDatabase);
                LOGGER.info("RegistrationService::register created tenant " + tenant.getId().toString());

                // 3. Create a Cognito User Pool for this tenant now that we have its
                // unique id, and then create a new user in that pool from the registration
                String userPoolId = createUserPool(tenant, registration);
                LOGGER.info("RegistrationService::register created user pool " + userPoolId);
                // Skip this for now since we don't use it and can't seem to avoid the cold start
                // tenant = updateTenantUserPool(tenant, userPoolId);
                tenant.setUserPool(userPoolId);
                createUser(tenant, registration);
                LOGGER.info("RegistrationService::register created user " + registration.getFirstName() + " " + registration.getLastName());

                // 4. Save this tenant's environment variables to parameter store
                storeParameters(tenant);

                // 5. Now provision this tenant's silo infrastructure (async)
                String stackName = createStack(tenant);

                response = new APIGatewayProxyResponseEvent()
                        .withStatusCode(HttpURLConnection.HTTP_OK)
                        .withBody(Utils.toJson(Map.of("TenantId", tenant.getId().toString(), "StackName", stackName)))
                        .withHeaders(CORS);
            } catch (Exception e) {
                LOGGER.error(Utils.getFullStackTrace(e));
                response = new APIGatewayProxyResponseEvent()
                        .withBody(Utils.toJson(Map.of("message", e.getMessage())))
                        .withHeaders(CORS)
                        .withStatusCode(HttpURLConnection.HTTP_BAD_REQUEST);
            }
        } else {
            response = new APIGatewayProxyResponseEvent()
                    .withStatusCode(HttpURLConnection.HTTP_BAD_REQUEST)
                    .withHeaders(CORS)
                    .withBody(Utils.toJson(Map.of("message", "request body invalid")));
        }

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("RegistrationService::register exec " + totalTimeMillis);
        return response;
    }

//    protected String nextAvailableDatabase() throws Exception {
//        long startTimeMillis = System.currentTimeMillis();
//        LOGGER.info("RegistrationService::nextAvailableDatabase");
//        URI invokeURL = URI.create(apiGatewayEndpoint + "/tenants/pool/database");
//        HttpURLConnection apiGateway = (HttpURLConnection) invokeURL.toURL().openConnection();
//        apiGateway.setRequestMethod("GET");
//        apiGateway.setRequestProperty("Accept", "application/json");
//        apiGateway.setRequestProperty("Content-Type", "application/json");
//
//        LOGGER.info("RegistrationService::createTenant Invoking API Gateway at " + invokeURL.toString());
//
//        if (apiGateway.getResponseCode() >= 400) {
//            throw new Exception(IoUtils.toUtf8String(apiGateway.getErrorStream()));
//        }
//        String result = IoUtils.toUtf8String(apiGateway.getInputStream());
//        LOGGER.info("RegistrationService::nextAvailableDatabse TenantService call \n" + result);
//
//        apiGateway.disconnect();
//        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
//        LOGGER.info("RegistrationService::nextAvailableDatabase exec " + totalTimeMillis);
//        return result;
//    }

    protected Map<String, String> nextAvailableDatabase() {
        long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("RegistrationService::nextAvailableDatabase");
        Map<String, String> availableDatabase = new HashMap<>();
        try {
            // Get the records from our pool management table that haven't
            // been assigned to a tenant yet
            ScanResponse response = ddb.scan(request -> request
                    .tableName("saas-factory-srvls-wrkshp-rds-clusters")
                    .filterExpression("attribute_not_exists(TenantId)")
            );
            if (!response.items().isEmpty()) {
                Map<String, AttributeValue> item = response.items().get(0);
                availableDatabase.put("DBClusterIdentifier", item.get("DBClusterIdentifier").s());
                availableDatabase.put("Endpoint", item.get("Endpoint").s());
            }
        } catch (DynamoDbException e) {
            LOGGER.error(Utils.getFullStackTrace(e));
            throw new RuntimeException(e);
        }
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("RegistrationService::nextAvailableDatabase exec {}", totalTimeMillis);
        return availableDatabase;
    }

    protected Tenant createTenant(String companyName, String plan, String database) throws Exception {
        long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("RegistrationService::createTenant {}", companyName);
        Tenant tenant = new Tenant(null, Boolean.TRUE, companyName, plan, null, database);

        URI invokeURL = URI.create(apiGatewayEndpoint + "/tenants");
        HttpURLConnection apiGateway = (HttpURLConnection) invokeURL.toURL().openConnection();
        apiGateway.setDoOutput(true);
        apiGateway.setRequestMethod("POST");
        apiGateway.setRequestProperty("Accept", "application/json");
        apiGateway.setRequestProperty("Content-Type", "application/json");

        LOGGER.info("RegistrationService::createTenant Invoking API Gateway at {}", invokeURL);
        OutputStream body = apiGateway.getOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(body, StandardCharsets.UTF_8);
        writer.write(Utils.toJson(tenant));
        writer.flush();
        writer.close();
        body.close();

        if (apiGateway.getResponseCode() >= 400) {
            throw new Exception(IoUtils.toUtf8String(apiGateway.getErrorStream()));
        }
        Tenant result = Utils.fromJson(apiGateway.getInputStream(), Tenant.class);
        apiGateway.disconnect();
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("RegistrationService::createTenant exec {}", totalTimeMillis);
        return result;
    }

    protected String createUserPool(Tenant tenant, Registration registration) {
        LOGGER.info("RegistrationService::createUserPool");
        String tenantId = tenant.getId().toString();
        String poolName = tenantId.substring(0, 8);
        CreateUserPoolResponse createUserPoolResponse = cognito.createUserPool(request -> request
                .poolName(poolName + "_UserPool")
                .schema(
                        SchemaAttributeType.builder()
                                .attributeDataType(AttributeDataType.STRING)
                                .name("email")
                                .required(Boolean.TRUE)
                                .build(),
                        SchemaAttributeType.builder()
                                .attributeDataType(AttributeDataType.STRING)
                                .name("given_name")
                                .required(Boolean.TRUE)
                                .build(),
                        SchemaAttributeType.builder()
                                .attributeDataType(AttributeDataType.STRING)
                                .name("family_name")
                                .required(Boolean.TRUE)
                                .build(),
                        SchemaAttributeType.builder()
                                .attributeDataType(AttributeDataType.STRING)
                                .name("tenant_id")
                                .required(Boolean.FALSE) // Custom attributes can't be required
                                .mutable(Boolean.FALSE)
                                .build(),
                        SchemaAttributeType.builder()
                                .attributeDataType(AttributeDataType.STRING)
                                .name("company")
                                .required(Boolean.FALSE) // Custom attributes can't be required
                                .mutable(Boolean.TRUE)
                                .build(),
                        SchemaAttributeType.builder()
                                .attributeDataType(AttributeDataType.STRING)
                                .name("plan")
                                .required(Boolean.FALSE) // Custom attributes can't be required
                                .mutable(Boolean.TRUE)
                                .build()
                )
                .adminCreateUserConfig(
                        AdminCreateUserConfigType.builder()
                                .allowAdminCreateUserOnly(Boolean.TRUE)
                                .build()
                )
                .policies(
                        UserPoolPolicyType.builder()
                                .passwordPolicy(
                                        PasswordPolicyType.builder()
                                                .minimumLength(8)
                                                .requireLowercase(Boolean.TRUE)
                                                .requireUppercase(Boolean.TRUE)
                                                .requireNumbers(Boolean.TRUE)
                                                .temporaryPasswordValidityDays(7)
                                                .build()
                                )
                        .build()
                )
        );
        UserPoolType userPool = createUserPoolResponse.userPool();

        LOGGER.info("RegistrationService::createUserPool create app client");
        CreateUserPoolClientResponse userPoolClientResponse = cognito.createUserPoolClient(request -> request
                .userPoolId(userPool.id())
                .clientName(poolName + "_AppClient")
                .generateSecret(Boolean.FALSE)
                .explicitAuthFlows(ExplicitAuthFlowsType.ADMIN_NO_SRP_AUTH)
        );

        return userPool.id();
    }

    protected Tenant updateTenantUserPool(Tenant tenant, String userPoolId) throws Exception {
        long startTimeMillis = System.currentTimeMillis();
        String tenantId = tenant.getId().toString();
        LOGGER.info("RegistrationService::updateTenantUserPool {}", tenantId);
        tenant.setUserPool(userPoolId);

        URI invokeURL = URI.create(apiGatewayEndpoint + "/tenants/" + tenantId + "/userpool");
        HttpURLConnection apiGateway = (HttpURLConnection) invokeURL.toURL().openConnection();
        apiGateway.setDoOutput(true);
        apiGateway.setRequestMethod("PUT");
        apiGateway.setRequestProperty("Accept", "application/json");
        apiGateway.setRequestProperty("Content-Type", "application/json");

        LOGGER.info("RegistrationService::updateTenantUserPool Invoking API Gateway at {}", invokeURL);
        OutputStream body = apiGateway.getOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(body, StandardCharsets.UTF_8);
        writer.write(Utils.toJson(tenant));
        writer.flush();
        writer.close();
        body.close();

        if (apiGateway.getResponseCode() >= 400) {
            throw new Exception(IoUtils.toUtf8String(apiGateway.getErrorStream()));
        }
        Tenant result = Utils.fromJson(apiGateway.getInputStream(), Tenant.class);
        apiGateway.disconnect();
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("RegistrationService::updateTenantUserPool exec {}", totalTimeMillis);
        return result;
    }

    protected String createUser(Tenant tenant, Registration registration) {
        LOGGER.info("RegistrationService::createUser create Cognito user {}", registration.getEmail());
        final String userPool = tenant.getUserPool();
        AdminCreateUserResponse createUserResponse = null;
        try {
            createUserResponse = cognito.adminCreateUser(request -> request
                    .userPoolId(userPool)
                    .username(registration.getEmail())
                    .userAttributes(
                            AttributeType.builder().name("email").value(registration.getEmail()).build(),
                            AttributeType.builder().name("family_name").value(registration.getLastName()).build(),
                            AttributeType.builder().name("given_name").value(registration.getFirstName()).build(),
                            AttributeType.builder().name("custom:tenant_id").value(tenant.getId().toString()).build(),
                            AttributeType.builder().name("custom:company").value(registration.getCompany()).build(),
                            AttributeType.builder().name("custom:plan").value(registration.getPlan()).build()
                    )
                    .temporaryPassword(Utils.generatePassword(12))
                    .desiredDeliveryMediumsWithStrings("EMAIL")
                    .messageAction("SUPPRESS")
            );
        } catch (SdkServiceException cognitoError) {
            LOGGER.error("CognitoIdentity::AdminCreateUser", cognitoError);
            LOGGER.error(Utils.getFullStackTrace(cognitoError));
        }
        final UserType user = createUserResponse.user();

        LOGGER.info("RegistrationService::createUser setting password");
        AdminSetUserPasswordResponse passwordResponse = cognito.adminSetUserPassword(request -> request
                .userPoolId(userPool)
                .username(user.username())
                .password(registration.getPassword())
                .permanent(Boolean.TRUE)
        );

//        UserStatusType status = cognito.adminGetUser(request -> request.userPoolId(userPool).username(user.username())).userStatus();
//        LOGGER.info("RegistrationService::createUser " + user.username() + " " + status.toString());
        return user.username();
    }

    protected void storeParameters(Tenant tenant) {
        long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("RegistrationService::storeParameters");
        Map<String, String> params = Map.of(
                "DB_NAME", "saas_factory_srvls_wrkshp",
                "DB_USER", "application",
                "DB_PASS", Utils.generatePassword(12),
                "DB_HOST", tenant.getDatabase()
        );

        params.forEach((key, value) -> {
            String param = tenant.getId().toString() + "_" + key;
            LOGGER.info("RegistrationService::storeParameters PutParameter " + param);
            ssm.putParameter(request -> request
                    .name(param)
                    .value(value)
                    .type(key.startsWith("DB_PASS") ? ParameterType.SECURE_STRING : ParameterType.STRING)
                    .overwrite(Boolean.TRUE)
            );
        });
        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("RegistrationService::storeParameters exec " + totalTimeMillis);
    }

    protected String createStack(Tenant tenant) {
        long startTimeMillis = System.currentTimeMillis();

        LOGGER.info("RegistrationService::createStack getting routing rule priority");
        int priority = 0;
        try {
            DescribeRulesResponse elbResponse = elbv2.describeRules(request -> request
                    .listenerArn(albListenerArn)
            );
            priority = elbResponse.rules().size() + 1;
        } catch (SdkServiceException e) {
            LOGGER.error(Utils.getFullStackTrace(e));
            throw new RuntimeException(e);
        }
        LOGGER.info("RegistrationService::createStack routing rule priority = " + priority);

        final Integer tenantAlbRulePriority = priority;
        String stackName = "Tenant-" + tenant.getId().toString().substring(0, 8);
        LOGGER.info("RegistrationService::createStack " + stackName);
        CreateStackResponse response = cfn.createStack(request -> request
                .stackName(stackName)
                .onFailure("DO_NOTHING")
                .capabilitiesWithStrings("CAPABILITY_NAMED_IAM")
                //.templateURL("https://" + workshopBucket + ".s3-" + System.getenv("AWS_REGION") + ".amazonaws.com/" + ONBOARDING_TEMPLATE)
                .templateURL("https://" + workshopBucket + ".s3.amazonaws.com/" + ONBOARDING_TEMPLATE)
                .parameters(
                        Parameter.builder().parameterKey("TenantId").parameterValue(tenant.getId().toString()).build(),
                        Parameter.builder().parameterKey("TenantRouteALBPriority").parameterValue(tenantAlbRulePriority.toString()).build(),
                        Parameter.builder().parameterKey("KeyPair").parameterValue(keyPairName).build(),
                        Parameter.builder().parameterKey("VPC").parameterValue(vpcId).build(),
                        Parameter.builder().parameterKey("PrivateSubnets").parameterValue(privateSubnetIds).build(),
                        Parameter.builder().parameterKey("AppServerSecurityGroup").parameterValue(applicationServerSecurityGroup).build(),
                        Parameter.builder().parameterKey("CodePipelineBucket").parameterValue(codePipelineBucket).build(),
                        Parameter.builder().parameterKey("CodeDeployApplication").parameterValue(codeDeployApplication).build(),
                        Parameter.builder().parameterKey("DeploymentGroup").parameterValue(deploymentGroup).build(),
                        Parameter.builder().parameterKey("LambdaUpdateDeploymentGroupArn").parameterValue(updateCodeDeployLambdaArn).build(),
                        Parameter.builder().parameterKey("ALBListener").parameterValue(albListenerArn).build(),
                        Parameter.builder().parameterKey("LambdaAddDatabaseUserArn").parameterValue(addDatabaseUserArn).build()
                )
        );
        LOGGER.info("RegistrationService::createStack stack id " + response.stackId());

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("RegistrationService::createStack CloudFormation CreateStack returned in " + totalTimeMillis);
        return stackName;
    }

}
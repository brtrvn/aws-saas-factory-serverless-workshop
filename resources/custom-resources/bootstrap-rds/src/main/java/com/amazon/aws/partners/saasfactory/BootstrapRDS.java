/**
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 * <p>
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public class BootstrapRDS implements RequestHandler<Map<String, Object>, Object> {

    private static final Logger LOGGER = LogManager.getLogger(BootstrapRDS.class);
    private final static String RDS_HOT_POOL_TABLE = System.getenv("RDS_HOT_POOL_TABLE");
    static final String SQL_STATEMENT_DELIMITER = ";\r?\n";
    static final String DOLLAR_QUOTED_DELIMITER = "\\$\\$;\r?\n";
    static final int MAX_SQL_BATCH_SIZE = 25;
    private final SecretsManagerClient secrets;
    private final DynamoDbClient ddb;

    public BootstrapRDS() {
        this.secrets = Utils.sdkClient(SecretsManagerClient.builder(), SecretsManagerClient.SERVICE_NAME);
        this.ddb = Utils.sdkClient(DynamoDbClient.builder(), DynamoDbClient.SERVICE_NAME);
    }

    @Override
    public Object handleRequest(Map<String, Object> event, Context context) {
        Utils.logRequestEvent(event);

        final Map<String, Object> resourceProperties = (Map<String, Object>) event.get("ResourceProperties");
        String action = (String) resourceProperties.get("Action");
        switch (action) {
            case "BOOTSTRAP":
                bootstrap(event, context);
                break;
            case "BOOTSTRAP_POOL":
                bootstrapPool(event, context);
                break;
            case "ADD_USER":
                addApplicationUser(event, context);
                break;
            default:
                return null;
        }
        return null;
    }

    public void bootstrap(Map<String, Object> event, Context context) {
        String requestType = (String) event.get("RequestType");
        Map<String, Object> resourceProperties = (Map<String, Object>) event.get("ResourceProperties");
        String superUserSecret = (String) resourceProperties.get("SuperUserCredentials");
        String appUserSecret = (String) resourceProperties.get("AppUserCredentials");
        String host = (String) resourceProperties.get("Host");
        String database = (String) resourceProperties.get("Database");
        String instanceId = (String) resourceProperties.get("InstanceId");
        String tenantId = (String) resourceProperties.get("TenantId");

        ExecutorService service = Executors.newSingleThreadExecutor();
        Map<String, Object> responseData = new HashMap<>();
        try {
            Runnable r = () -> {
                if ("Create".equalsIgnoreCase(requestType)) {
                    LOGGER.info("CREATE");

                    Credential superUserCredentials = getCredentials(secrets, superUserSecret);
                    Properties superUser = new Properties();
                    superUser.put("user", superUserCredentials.username());
                    superUser.put("password", superUserCredentials.password());

                    String jdbcUrl = "jdbc:postgresql://" + host + ":5432/" + database;

                    // Create the initial table schema. The RDS superuser will be
                    // the owner of these tables.
                    LOGGER.info("Creating monolith database objects");
                    try (Connection connection = DriverManager.getConnection(jdbcUrl, superUser);
                            Statement sql = connection.createStatement()) {
                        connection.setAutoCommit(false);
                        int batch = 0;
                        InputStream bootstrapSql = Thread.currentThread().getContextClassLoader()
                                .getResourceAsStream("bootstrap.sql");
                        Scanner bootstrapSqlScanner = new Scanner(bootstrapSql, StandardCharsets.UTF_8);
                        bootstrapSqlScanner.useDelimiter(Pattern.compile(SQL_STATEMENT_DELIMITER));
                        while (bootstrapSqlScanner.hasNext()) {
                            String ddl = bootstrapSqlScanner.next().trim();
                            if (!ddl.isEmpty()) {
                                //LOGGER.info(String.format("%02d %s", ++batch, ddl));
                                sql.addBatch(ddl);
                                batch++;
                                if (batch % MAX_SQL_BATCH_SIZE == 0) {
                                    sql.executeBatch();
                                    connection.commit();
                                    sql.clearBatch();
                                }
                            }
                        }
                        sql.executeBatch();
                        connection.commit();

                        // Add some mock data to the tables for the lab 1 "monolith" user
                        LOGGER.info("Inserting monolith database data");
                        if ("MONOLITH".equalsIgnoreCase(tenantId)) {
                            InputStream dataSql = Thread.currentThread().getContextClassLoader()
                                    .getResourceAsStream("data.sql");
                            Scanner dataSqlScanner = new Scanner(dataSql, StandardCharsets.UTF_8);
                            dataSqlScanner.useDelimiter(Pattern.compile(SQL_STATEMENT_DELIMITER));
                            while (dataSqlScanner.hasNext()) {
                                String inserts = dataSqlScanner.next().trim();
                                if (!inserts.isEmpty()) {
                                    sql.addBatch(inserts);
                                    batch++;
                                    if (batch % MAX_SQL_BATCH_SIZE == 0) {
                                        sql.executeBatch();
                                        connection.commit();
                                        sql.clearBatch();
                                    }
                                }
                            }
                        }
                        sql.executeBatch();
                        connection.commit();

                        // Use the RDS master user to create a new read/write non-root
                        // user for our app to access the database as.
                        if (Utils.isNotEmpty(appUserSecret)) {
                            LOGGER.info("Creating application user for monolith database");
                            Credential appUserCredentials = getCredentials(secrets, appUserSecret);

                            InputStream userSql = Thread.currentThread().getContextClassLoader()
                                    .getResourceAsStream("user.sql");
                            Scanner userSqlScanner = new Scanner(userSql, StandardCharsets.UTF_8);
                            Pattern dollarQuoted = Pattern.compile(DOLLAR_QUOTED_DELIMITER);
                            userSqlScanner.useDelimiter(dollarQuoted);
                            while (userSqlScanner.hasNext()) {
                                // Simple variable replacement in the SQL
                                String ddl = userSqlScanner.next()
                                        .replace("{{DB_APP_USER}}", appUserCredentials.username())
                                        .replace("{{DB_APP_PASS}}", appUserCredentials.password())
                                        .trim()
                                        + Objects.toString(userSqlScanner.findWithinHorizon(dollarQuoted, 0), "").trim();
                                if (Utils.isNotEmpty(ddl)) {
                                    sql.addBatch(ddl);
                                }
                            }
                            sql.executeBatch();
                            connection.commit();
                        }

                        // Keep a list of bootstrapped databases for use in Lab 2
                        // Convenient to do it here because this Lambda is already
                        // a VPC function and is called from CloudFormation after
                        // creating the RDS instances
                        Map<String, AttributeValue> item = new HashMap<>();
                        item.put("instance", AttributeValue.builder().s(instanceId).build());
                        item.put("host", AttributeValue.builder().s(host).build());
                        if (Utils.isNotEmpty(tenantId)) {
                            item.put("tenant_id", AttributeValue.builder().s(tenantId).build());
                        }
                        try {
                            LOGGER.info("Adding database instance {} to warm pool", instanceId);
                            ddb.putItem(request -> request.tableName(RDS_HOT_POOL_TABLE).item(item));
                        } catch (DynamoDbException e) {
                            throw new RuntimeException(e);
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    CloudFormationResponse.send(event, context, "SUCCESS", responseData);
                } else if ("Update".equalsIgnoreCase(requestType)) {
                    LOGGER.info("UPDATE");
                    CloudFormationResponse.send(event, context, "SUCCESS", responseData);
                } else if ("Delete".equalsIgnoreCase(requestType)) {
                    LOGGER.info("DELETE");
                    CloudFormationResponse.send(event, context, "SUCCESS", responseData);
                } else {
                    LOGGER.error("FAILED unknown requestType {}", requestType);
                    responseData.put("Reason", "Unknown RequestType " + requestType);
                    CloudFormationResponse.send(event, context, "FAILED", responseData);
                }
            };
            Future<?> f = service.submit(r);
            f.get(context.getRemainingTimeInMillis() - 1000, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException | InterruptedException | ExecutionException e) {
            // Timed out
            LOGGER.error("FAILED unexpected error or request timed out", e);
            LOGGER.error(Utils.getFullStackTrace(e));
            responseData.put("Reason", e.getMessage());
            CloudFormationResponse.send(event, context, "FAILED", responseData);
        } finally {
            service.shutdown();
        }
    }

    public void addApplicationUser(Map<String, Object> event, Context context) {
        String requestType = (String) event.get("RequestType");
        Map<String, Object> resourceProperties = (Map<String, Object>) event.get("ResourceProperties");
        String superUserSecret = (String) resourceProperties.get("SuperUserCredentials");
        String appUserSecret = (String) resourceProperties.get("AppUserCredentials");
        String host = (String) resourceProperties.get("Host");
        String database = (String) resourceProperties.get("Database");
        String instanceId = (String) resourceProperties.get("InstanceId");
        String tenantId = (String) resourceProperties.get("TenantId");

        ExecutorService service = Executors.newSingleThreadExecutor();
        Map<String, Object> responseData = new HashMap<>();
        try {
            Runnable r = () -> {
                if ("Create".equalsIgnoreCase(requestType)) {
                    LOGGER.info("CREATE");

                    Credential superUserCredentials = getCredentials(secrets, superUserSecret);
                    Properties superUser = new Properties();
                    superUser.put("user", superUserCredentials.username());
                    superUser.put("password", superUserCredentials.password());

                    Credential appUserCredentials = getCredentials(secrets, appUserSecret);

                    String jdbcUrl = "jdbc:postgresql://" + host + ":5432/" + database;

                    try (Connection connection = DriverManager.getConnection(jdbcUrl, superUser);
                         Statement sql = connection.createStatement()) {
                        connection.setAutoCommit(false);
                        InputStream userSql = Thread.currentThread().getContextClassLoader()
                                .getResourceAsStream("user.sql");
                        Scanner userSqlScanner = new Scanner(userSql, StandardCharsets.UTF_8);
                        userSqlScanner.useDelimiter(Pattern.compile(SQL_STATEMENT_DELIMITER));
                        Pattern dollarQuoted = Pattern.compile(DOLLAR_QUOTED_DELIMITER);
                        userSqlScanner.useDelimiter(dollarQuoted);
                        while (userSqlScanner.hasNext()) {
                            // Simple variable replacement in the SQL
                            String ddl = userSqlScanner.next()
                                    .replace("{{DB_APP_USER}}", appUserCredentials.username())
                                    .replace("{{DB_APP_PASS}}", appUserCredentials.password())
                                    .trim()
                                    + Objects.toString(userSqlScanner.findWithinHorizon(dollarQuoted, 0), "").trim();
                            if (Utils.isNotEmpty(ddl)) {
                                sql.addBatch(ddl);
                            }
                        }
                        sql.executeBatch();
                        connection.commit();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    CloudFormationResponse.send(event, context, "SUCCESS", responseData);
                } else if ("Update".equalsIgnoreCase(requestType)) {
                    LOGGER.info("UPDATE");
                    CloudFormationResponse.send(event, context, "SUCCESS", responseData);
                } else if ("Delete".equalsIgnoreCase(requestType)) {
                    LOGGER.info("DELETE");
                    CloudFormationResponse.send(event, context, "SUCCESS", responseData);
                } else {
                    LOGGER.error("FAILED unknown requestType {}", requestType);
                    responseData.put("Reason", "Unknown RequestType " + requestType);
                    CloudFormationResponse.send(event, context, "FAILED", responseData);
                }
            };
            Future<?> f = service.submit(r);
            f.get(context.getRemainingTimeInMillis() - 1000, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException | InterruptedException | ExecutionException e) {
            // Timed out
            LOGGER.error("FAILED unexpected error or request timed out", e);
            LOGGER.error(Utils.getFullStackTrace(e));
            responseData.put("Reason", e.getMessage());
            CloudFormationResponse.send(event, context, "FAILED", responseData);
        } finally {
            service.shutdown();
        }
    }

    public void bootstrapPool(Map<String, Object> event, Context context) {
        String requestType = (String) event.get("RequestType");
        Map<String, Object> resourceProperties = (Map<String, Object>) event.get("ResourceProperties");
        String superUserSecret = (String) resourceProperties.get("SuperUserCredentials");
        String appUserSecret = (String) resourceProperties.get("AppUserCredentials");
        String host = (String) resourceProperties.get("Host");
        String database = (String) resourceProperties.get("Database");
        String instanceId = (String) resourceProperties.get("InstanceId");
        String tenantId = (String) resourceProperties.get("TenantId");

        ExecutorService service = Executors.newSingleThreadExecutor();
        Map<String, Object> responseData = new HashMap<>();
        try {
            Runnable r = () -> {
                if ("Create".equalsIgnoreCase(requestType)) {
                    LOGGER.info("CREATE");

                    Credential superUserCredentials = getCredentials(secrets, superUserSecret);
                    Properties superUser = new Properties();
                    superUser.put("user", superUserCredentials.username());
                    superUser.put("password", superUserCredentials.password());

                    String jdbcUrl = "jdbc:postgresql://" + host + ":5432/" + database;
                    try (Connection connection = DriverManager.getConnection(jdbcUrl, superUser);
                         Statement sql = connection.createStatement()) {
                        connection.setAutoCommit(false);
                        int batch = 0;
                        InputStream bootstrapSql = Thread.currentThread().getContextClassLoader()
                                .getResourceAsStream("bootstrap_pool.sql");
                        Scanner bootstrapSqlScanner = new Scanner(bootstrapSql, StandardCharsets.UTF_8);
                        bootstrapSqlScanner.useDelimiter(Pattern.compile(SQL_STATEMENT_DELIMITER));
                        while (bootstrapSqlScanner.hasNext()) {
                            String ddl = bootstrapSqlScanner.next().trim();
                            if (!ddl.isEmpty()) {
                                //LOGGER.info(String.format("%02d %s", ++batch, ddl));
                                sql.addBatch(ddl);
                                batch++;
                                if (batch % MAX_SQL_BATCH_SIZE == 0) {
                                    sql.executeBatch();
                                    connection.commit();
                                    sql.clearBatch();
                                }
                            }
                        }
                        sql.executeBatch();
                        connection.commit();

                        // Use the RDS master user to create a new read/write non-root
                        // user for our app to access the database as.
                        if (Utils.isNotEmpty(appUserSecret)) {
                            LOGGER.info("Creating application user for monolith database");
                            Credential appUserCredentials = getCredentials(secrets, appUserSecret);

                            InputStream userSql = Thread.currentThread().getContextClassLoader()
                                    .getResourceAsStream("user.sql");
                            Scanner userSqlScanner = new Scanner(userSql, StandardCharsets.UTF_8);
                            Pattern dollarQuoted = Pattern.compile(DOLLAR_QUOTED_DELIMITER);
                            userSqlScanner.useDelimiter(dollarQuoted);
                            while (userSqlScanner.hasNext()) {
                                // Simple variable replacement in the SQL
                                String ddl = userSqlScanner.next()
                                        .replace("{{DB_APP_USER}}", appUserCredentials.username())
                                        .replace("{{DB_APP_PASS}}", appUserCredentials.password())
                                        .trim()
                                        + Objects.toString(userSqlScanner.findWithinHorizon(dollarQuoted, 0), "").trim();
                                if (Utils.isNotEmpty(ddl)) {
                                    sql.addBatch(ddl);
                                }
                            }
                            sql.executeBatch();
                            connection.commit();
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    CloudFormationResponse.send(event, context, "SUCCESS", responseData);
                } else if ("Update".equalsIgnoreCase(requestType)) {
                    LOGGER.info("UPDATE");
                    CloudFormationResponse.send(event, context, "SUCCESS", responseData);
                } else if ("Delete".equalsIgnoreCase(requestType)) {
                    LOGGER.info("DELETE");
                    CloudFormationResponse.send(event, context, "SUCCESS", responseData);
                } else {
                    LOGGER.error("FAILED unknown requestType {}", requestType);
                    responseData.put("Reason", "Unknown RequestType " + requestType);
                    CloudFormationResponse.send(event, context, "FAILED", responseData);
                }
            };
            Future<?> f = service.submit(r);
            f.get(context.getRemainingTimeInMillis() - 1000, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException | InterruptedException | ExecutionException e) {
            // Timed out
            LOGGER.error("FAILED unexpected error or request timed out", e);
            LOGGER.error(Utils.getFullStackTrace(e));
            responseData.put("Reason", e.getMessage());
            CloudFormationResponse.send(event, context, "FAILED", responseData);
        } finally {
            service.shutdown();
        }
    }

    protected Credential getCredentials(SecretsManagerClient secrets, String secretId) {
        try {
            GetSecretValueResponse response = secrets.getSecretValue(request -> request.secretId(secretId));
            Map<String, String> json = Utils.fromJson(response.secretString(), HashMap.class);
            return new Credential(json.get("username"), json.get("password"));
        } catch (SdkServiceException secretsError) {
            LOGGER.error("Secrets Manager call failed", secretsError);
            throw secretsError;
        }
    }

    record Credential(String username, String password){}

}

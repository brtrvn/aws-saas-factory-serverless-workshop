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
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.net.HttpURLConnection;
import java.util.*;
import java.util.stream.Collectors;

public class AuthService implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);
    private static final Map<String, String> CORS = Map.of("Access-Control-Allow-Origin", "*");

    private final CognitoIdentityProviderClient cognito;

    public AuthService() {
        this.cognito = Utils.sdkClient(CognitoIdentityProviderClient.builder(), CognitoIdentityProviderClient.SERVICE_NAME);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        //logRequestEvent(event);
        if (Utils.warmup(event)) {
            LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(HttpURLConnection.HTTP_OK);
        }

        APIGatewayProxyResponseEvent response = null;
        try {
            Map<String, String> signin = Utils.fromJson(event.getBody(), HashMap.class);
            if (signin != null && !signin.isEmpty()) {
                String username = signin.get("username");
                String password = signin.get("password");

//                String userPoolId = findUserPool(username);
                List<String> userPoolIds = findUserPools(username);

                String userPoolId = userPoolIds.get(0);
                String appClientId = appClient(userPoolId);
                AdminInitiateAuthResponse authResponse = null;
                try {
                    authResponse = cognito.adminInitiateAuth(request -> request
                            .userPoolId(userPoolId)
                            .clientId(appClientId)
                            .authFlow(AuthFlowType.ADMIN_NO_SRP_AUTH)
                            .authParameters(Map.of("USERNAME", username, "PASSWORD", password))
                    );

                    String challenge = authResponse.challengeNameAsString();
                    if (challenge != null && !challenge.isEmpty()) {
                        response = new APIGatewayProxyResponseEvent()
                                .withBody(Utils.toJson(Map.of("message", challenge)))
                                .withStatusCode(HttpURLConnection.HTTP_UNAUTHORIZED);
                    } else {
                        AuthenticationResultType auth = authResponse.authenticationResult();
                        CognitoAuthResult result = CognitoAuthResult.builder()
                                .accessToken(auth.accessToken())
                                .idToken(auth.idToken())
                                .expiresIn(auth.expiresIn())
                                .refreshToken(auth.refreshToken())
                                .tokenType(auth.tokenType())
                                .build();

                        response = new APIGatewayProxyResponseEvent()
                                .withBody(Utils.toJson(result))
                                .withHeaders(CORS)
                                .withStatusCode(HttpURLConnection.HTTP_OK);
                    }
                } catch (SdkServiceException cognitoError) {
                    LOGGER.error("CognitoIdentity::AdminInitiateAuth", cognitoError);
                    LOGGER.error(Utils.getFullStackTrace(cognitoError));
                    response = new APIGatewayProxyResponseEvent()
                            .withBody(Utils.toJson(Map.of("message", cognitoError.getMessage())))
                            .withStatusCode(HttpURLConnection.HTTP_UNAUTHORIZED);
                }
            } else {
                response = new APIGatewayProxyResponseEvent()
                        .withStatusCode(HttpURLConnection.HTTP_BAD_REQUEST)
                        .withBody(Utils.toJson(Map.of("message", "request body invalid")));
            }
        } catch (Exception e) {
            LOGGER.error(Utils.getFullStackTrace(e));
            response = new APIGatewayProxyResponseEvent()
                    .withBody(Utils.toJson(Map.of("message", e.getMessage())))
                    .withStatusCode(HttpURLConnection.HTTP_BAD_REQUEST);
        }
        return response;
    }

    protected List<String> findUserPools(String username) {
        List<String> poolsWithUsername = new ArrayList<>();
        String userPoolId = null;
        ListUserPoolsResponse userPoolsResponse = cognito.listUserPools(request -> request.maxResults(60));
        List<UserPoolDescriptionType> userPools = userPoolsResponse.userPools();
        if (userPools != null) {
            for (UserPoolDescriptionType userPool : userPools) {
                ListUsersResponse usersResponse = cognito.listUsers(request -> request
                        .userPoolId(userPool.id())
                );
                List<UserType> users = usersResponse.users();
                if (users != null) {
                    for (UserType user : users) {
                        Map<String, String> attributes = user.attributes()
                                .stream()
                                .map(a -> new AbstractMap.SimpleEntry<>(a.name(), a.value()))
                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                        CognitoUser cognitoUser = CognitoUser.builder()
                                .username(user.username())
                                .status(user.userStatusAsString())
                                .attributes(attributes)
                                .build();
                        LOGGER.info(Utils.toJson(cognitoUser));
                        if (username.equals(user.username())) {
                            userPoolId = userPool.id();
                            poolsWithUsername.add(userPoolId);
//                            break;
                        }
                    }
                }
            }
        }
//        return userPoolId;
        for (String poolId : poolsWithUsername) {
            LOGGER.info("Username {} in pool {}", username, poolId);
        }
        return poolsWithUsername;
    }

    protected String appClient(String userPoolId) {
        String appClientId = null;
        ListUserPoolClientsResponse appClientsResponse = cognito.listUserPoolClients(request -> request.userPoolId(userPoolId));
        List<UserPoolClientDescription> appClients = appClientsResponse.userPoolClients();
        if (appClients != null && !appClients.isEmpty()) {
            appClientId = appClients.get(0).clientId();
        }
        return appClientId;
    }

}
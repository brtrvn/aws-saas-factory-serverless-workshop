/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.amazon.aws.partners.saasfactory;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.internal.retry.SdkDefaultRetrySetting;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy;
import software.amazon.awssdk.core.retry.conditions.RetryCondition;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.partitionmetadata.AwsCnPartitionMetadata;
import software.amazon.awssdk.regions.partitionmetadata.AwsUsGovPartitionMetadata;

import java.io.*;
import java.net.URI;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

public class Utils {

    private static final Logger LOGGER = LoggerFactory.getLogger(Utils.class);
    private static final DateFormat JAVASCRIPT_ISO8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX'Z'");

    static {
        JAVASCRIPT_ISO8601.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.findAndRegisterModules();
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MAPPER.setDateFormat(JAVASCRIPT_ISO8601);
        MAPPER.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        MAPPER.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
        MAPPER.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
    }

    static final char[] LOWERCASE_LETTERS = {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z'};
    static final char[] UPPERCASE_LETTERS = {'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z'};
    static final char[] NUMBERS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'};
    static final char[] SYMBOLS = {'!', '#', '$', '%', '&', '*', '+', '-', '.', ':', '=', '?', '^', '_'};

    // We shouldn't be instantiated by callers
    private Utils() {
    }

    public static String escapeJson(String json) {
        return String.valueOf(JsonStringEncoder.getInstance().quoteAsString(json));
    }

    public static String unescapeJson(String quotedJson) {
        StringBuilder json = new StringBuilder();
        int index = 0;
        while (index < quotedJson.length()) {
            char current = quotedJson.charAt(index);
            index++;
            if (current == '\\' && index < quotedJson.length()) {
                char escapedCharacter = quotedJson.charAt(index);
                index++;

                if (escapedCharacter == '"' || escapedCharacter == '\\' || escapedCharacter == '/'
                        || escapedCharacter == '\'') {
                    // If the character after the backslash is another slash or a quote
                    // then add it to the JSON string we're building. Normal use case is
                    // that the next character should be a double quote mark.
                    json.append(escapedCharacter);
                } else if (escapedCharacter == 'n') {
                    // newline escape sequence
                    json.append('\n');
                } else if (escapedCharacter == 'r') {
                    // linefeed escape sequence
                    json.append('\r');
                } else if (escapedCharacter == 't') {
                    // tab escape sequence
                    json.append('\t');
                } else if (escapedCharacter == 'u') {
                    // unicode escape sequence should be 4 characters long
                    if ((index + 4) <= quotedJson.length()) {
                        StringBuilder hexadecimal = new StringBuilder();
                        for (char hex : quotedJson.substring(current, (current + 4)).toCharArray()) {
                            if (Character.isLetterOrDigit(hex)) {
                                hexadecimal.append(Character.toLowerCase(hex));
                            }
                        }
                        int codepoint = Integer.parseInt(hexadecimal.toString(), 16);
                        json.append((char) codepoint);
                        index += 4;
                    }
                } // ignorning bell and formfeed
            } else {
                // Non escaped, normal character
                json.append(current);
            }
        }
        return json.toString();
    }

    public static String toJson(Object obj) {
        String json = null;
        try {
            json = MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            LOGGER.error(Utils.getFullStackTrace(e));
        }
        return json;
    }

    public static <T> TreeNode toJsonTree(T convertibleObject) {
        return MAPPER.valueToTree(convertibleObject);
    }

    public static String toQuotedJson(Object obj) {
        return escapeJson(toJson(obj));
    }

    public static <T> T fromQuotedJson(String json, Class<T> serializeTo) {
        return fromJson(unescapeJson(json), serializeTo);
    }

    public static <T> T fromJson(String json, Class<T> serializeTo) {
        T object = null;
        try {
            object = MAPPER.readValue(json, serializeTo);
        } catch (Exception e) {
            LOGGER.error(Utils.getFullStackTrace(e));
        }
        return object;
    }

    public static <T> T fromJson(InputStream json, Class<T> serializeTo) {
        T object = null;
        try {
            object = MAPPER.readValue(json, serializeTo);
        } catch (Exception e) {
            LOGGER.error(Utils.getFullStackTrace(e));
        }
        return object;
    }

    public static Map<String, Object> asMap(Object obj) {
        return fromJson(toJson(obj), LinkedHashMap.class);
    }

    public static boolean isChinaRegion(String region) {
        return isChinaRegion(Region.of(region));
    }

    public static boolean isChinaRegion(Region region) {
        return region.metadata().partition() instanceof AwsCnPartitionMetadata;
    }

    public static boolean isGovCloudRegion(String region) {
        return isGovCloudRegion(Region.of(region));
    }

    public static boolean isGovCloudRegion(Region region) {
        return region.metadata().partition() instanceof AwsUsGovPartitionMetadata;
    }

    public static String endpointSuffix(String region) {
        return endpointSuffix(Region.of(region));
    }

    public static String endpointSuffix(Region region) {
        return region.metadata().partition().dnsSuffix();
    }

    public static <B extends AwsSyncClientBuilder<B, C> & AwsClientBuilder<?, C>, C> C sdkClient(
            AwsSyncClientBuilder<B, C> builder, String service) {
        return sdkClient(builder, service, UrlConnectionHttpClient.builder());
    }

    public static <B extends AwsSyncClientBuilder<B, C> & AwsClientBuilder<?, C>, C> C sdkClient(
            AwsSyncClientBuilder<B, C> builder, String service, SdkHttpClient.Builder httpClientBuilder) {
        return sdkClient(builder, service, httpClientBuilder, EnvironmentVariableCredentialsProvider.create());
    }

    public static <B extends AwsSyncClientBuilder<B, C> & AwsClientBuilder<?, C>, C> C sdkClient(
            AwsSyncClientBuilder<B, C> builder, String service, SdkHttpClient.Builder httpClientBuilder,
            AwsCredentialsProvider credentialsProvider) {
        if (Utils.isBlank(System.getenv("AWS_REGION"))) {
            throw new IllegalStateException("Missing required environment variable AWS_REGION");
        }
        Region region = Region.of(System.getenv("AWS_REGION"));

        // PartitionMetadata and ServiceMetadata do not generate the
        // correct service endpoints for all services
        String endpoint = "https://" + service + "." + region.id() + "." + endpointSuffix(region);

        // See https://docs.aws.amazon.com/general/latest/gr/r53.html
        if ("route53".equals(service)) {
            if (!isChinaRegion(region)) {
                region = Region.US_EAST_1;
                endpoint = "https://route53.amazonaws.com";
            } else {
                region = Region.CN_NORTHWEST_1;
                endpoint = "https://route53.amazonaws.com.cn";
            }
        }

        // See https://docs.aws.amazon.com/general/latest/gr/iam-service.html
        if ("iam".equals(service)) {
            if (isChinaRegion(region)) {
                // China's IAM endpoints are regional
                // See https://docs.amazonaws.cn/en_us/aws/latest/userguide/iam.html
            } else if (isGovCloudRegion(region)) {
                // TODO double check if we are supposed to use Region.AWS_GLOBAL
                region = Region.AWS_US_GOV_GLOBAL;
                endpoint = "https://iam.us-gov.amazonaws.com";
            } else {
                region = Region.AWS_GLOBAL;
                endpoint = "https://iam.amazonaws.com";
            }
        }

        C client = builder
                .httpClientBuilder(httpClientBuilder)
                .credentialsProvider(credentialsProvider)
                .region(region)
                .endpointOverride(URI.create(endpoint))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryPolicy(RetryPolicy.builder()
                                .backoffStrategy(BackoffStrategy.defaultStrategy())
                                .throttlingBackoffStrategy(BackoffStrategy.defaultThrottlingStrategy())
                                .numRetries(SdkDefaultRetrySetting.defaultMaxAttempts())
                                .retryCondition(RetryCondition.defaultRetryCondition())
                                .build()
                        )
                        .build()
                )
                .build();
        return client;
    }

    public static boolean isEmpty(String str) {
        return (str == null || str.isEmpty());
    }

    public static boolean isBlank(String str) {
        return (str == null || str.isBlank());
    }

    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }

    public static boolean isNotBlank(String str) {
        return !isBlank(str);
    }

    public static String toUpperSnakeCase(String str) {
        if (str == null) {
            return  null;
        }
        return toSnakeCase(str).toUpperCase();
    }

    public static String toSnakeCase(String str) {
        if (str == null) {
            return null;
        }
        if (str.isBlank()) {
            return str;
        }
        if (str.length() == 1) {
            return str.toLowerCase();
        }
        StringBuilder buffer = new StringBuilder();
        boolean skip = false;
        char[] chars = str.toCharArray();
        for (int ch = 0; ch < chars.length; ch++) {
            char character = chars[ch];
            if (ch == 0) {
                buffer.append(Character.toLowerCase(character));
                continue;
            }
            if ('_' == character || '-' == character || ' ' == character) {
                buffer.append('_');
                skip = true;
                continue;
            }
            if (Character.isLowerCase(character) || Character.isDigit(character)) {
                buffer.append(character);
                continue;
            }
            char previous = chars[(ch - 1)];
            if (!Character.isLetter(previous) || Character.isLowerCase(previous)) {
                if (skip) {
                    skip = false;
                } else {
                    buffer.append('_');
                }
                buffer.append(Character.toLowerCase(character));
                continue;
            }
            if (ch < (chars.length - 1)) {
                char last = chars[(ch + 1)];
                if (Character.isLowerCase(last)) {
                    if (skip) {
                        skip = false;
                    } else {
                        buffer.append('_');
                    }
                    buffer.append(Character.toLowerCase(character));
                    continue;
                }
            }
            buffer.append(Character.toLowerCase(character));
        }
        return buffer.toString();
    }

    public static String randomString(int length) {
        return randomString(length, null);
    }

    public static String randomString(int length, String allowedCharactersRegex) {
        if (length < 1) {
            throw new IllegalArgumentException("Minimum length is 1");
        }
        if (Utils.isBlank(allowedCharactersRegex)) {
            allowedCharactersRegex = "[^A-Za-z0-9]";
        }
        final Pattern regex = Pattern.compile(allowedCharactersRegex);
        final char[][] chars = {UPPERCASE_LETTERS, LOWERCASE_LETTERS, NUMBERS, SYMBOLS};
        Random random = new Random();
        StringBuilder buffer = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int bucket = random.nextInt(chars.length);
            buffer.append(chars[bucket][random.nextInt(chars[bucket].length)]);
        }
        char[] randomCharacters = buffer.toString().toCharArray();
        for (int ch = 0; ch < randomCharacters.length; ch++) {
            if (regex.matcher(String.valueOf(randomCharacters[ch])).matches()) {
                //LOGGER.info("Found unallowed character {}", randomCharacters[ch]);
                // Replace this character with one that's allowed
                while (true) {
                    int bucket = random.nextInt(chars.length);
                    char candidate = chars[bucket][random.nextInt(chars[bucket].length)];
                    if (!regex.matcher(String.valueOf(candidate)).matches()) {
                        //LOGGER.info("Replacing with {}", candidate);
                        randomCharacters[ch] = candidate;
                        break;
                    }
                    //LOGGER.info("Candidate {} is not allowed. Trying again.", candidate);
                }
            }
        }
        return String.valueOf(randomCharacters);
    }

    public static String generatePassword(int passwordLength) {
        if (passwordLength < 8) {
            throw new IllegalArgumentException("Invalid password length. Minimum of 8 characters is required.");
        }

        // Split the classes of characters into separate buckets so we can be sure to use
        // the correct amount of each type
        final char[][] chars = {UPPERCASE_LETTERS, LOWERCASE_LETTERS, NUMBERS, SYMBOLS};
        Random random = new Random();
        StringBuilder password = new StringBuilder(passwordLength);

        // Randomly select one character from each of the required character types
        ArrayList<Integer> reqCharBucket = new ArrayList<>(3);
        reqCharBucket.add(0, 0);
        reqCharBucket.add(1, 1);
        reqCharBucket.add(2, 2);
        reqCharBucket.add(3, 3);
        while (!reqCharBucket.isEmpty()) {
            Integer ranReqCharBucket = reqCharBucket.remove(random.nextInt(reqCharBucket.size()));
            password.append(chars[ranReqCharBucket][random.nextInt(chars[ranReqCharBucket].length)]);
        }

        // Fill out the rest of the password with randomly selected characters
        for (int i = 0; i < passwordLength - reqCharBucket.size(); i++) {
            int charBucket = random.nextInt(chars.length);
            password.append(chars[charBucket][random.nextInt(chars[charBucket].length)]);
        }
        return password.toString();
    }

    public static String getFullStackTrace(Throwable e) {
        final StringWriter sw = new StringWriter();
        final PrintWriter pw = new PrintWriter(sw, true);
        e.printStackTrace(pw);
        return sw.getBuffer().toString();
    }

    public static void logRequestEvent(Map<String, Object> event) {
        LOGGER.info(toJson(event));
    }

    public static void logRequestEvent(APIGatewayProxyRequestEvent event) {
        LOGGER.info(toJson(event));
    }

    public static boolean warmup(APIGatewayProxyRequestEvent event) {
        Map<String, String> queryParams = event.getQueryStringParameters();
        // Before parsing the request body, look to see if the request was
        // <uri>?source=warmup
        if (queryParams != null && "warmup".equals(queryParams.get("source"))) {
            return true;
        }
        if (isNotEmpty(event.getBody())) {
            try {
                // Don't know if request body is an array or an object
                // Ignore arrays. We're looking for {"source": "warmup"}
                JsonNode json = MAPPER.readTree(event.getBody());
                if (json.isObject()) {
                    Map<String, Object> body = MAPPER.treeToValue(json, LinkedHashMap.class);
                    if (body.containsKey("source") && "warmup".equals(body.get("source"))) {
                        return true;
                    }
                }
            } catch (JsonProcessingException jpe) {
                // swallow
            }
        }
        return false;
    }

    public static boolean warmup(Map<String, Object> event) {
        if ("warmup".equals(event.get("source"))) {
            // Lambda invocation _not_ through API Gateway
            return true;
        }
        if (event.containsKey("queryStringParameters")) {
            // Before parsing the request body, look to see if the request was
            // <uri>?source=warmup
            Map<String, String> queryParams = (Map<String, String>) event.get("queryStringParameters");
            if (queryParams != null && "warmup".equals(queryParams.get("source"))) {
                return true;
            }
        }
        if (event.containsKey("body") && isNotEmpty((String) event.get("body"))) {
            try {
                // Don't know if request body is an array or an object
                // Ignore arrays. We're looking for {"source": "warmup"}
                JsonNode json = MAPPER.readTree((String) event.get("body"));
                if (json.isObject()) {
                    Map<String, Object> body = MAPPER.treeToValue(json, LinkedHashMap.class);
                    if (body.containsKey("source") && "warmup".equals(body.get("source"))) {
                        return true;
                    }
                }
            } catch (JsonProcessingException jpe) {
                // swallow
            }
        }
        return false;
    }

    public static boolean nullableEquals(Object o1, Object o2) {
        // same reference or both null
        if (o1 == o2) {
            return true;
        }

        // if one is null but they aren't the same reference, they aren't equal
        if (o1 == null || o2 == null) {
            return false;
        }

        // if not the same class, not equal
        if (o1.getClass() != o2.getClass()) {
            return false;
        }

        return o1.equals(o2);
    }
}
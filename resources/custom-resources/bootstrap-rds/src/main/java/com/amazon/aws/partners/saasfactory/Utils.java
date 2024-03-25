package com.amazon.aws.partners.saasfactory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

public class Utils {

    private static final Logger LOGGER = LogManager.getLogger(Utils.class);
    static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.findAndRegisterModules();
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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

    public static String toJson(Object obj) {
        String json = null;
        try {
            json = MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            LOGGER.error(Utils.getFullStackTrace(e));
        }
        return json;
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

    public static String getFullStackTrace(Throwable e) {
        final StringWriter sw = new StringWriter();
        final PrintWriter pw = new PrintWriter(sw, true);
        e.printStackTrace(pw);
        return sw.getBuffer().toString();
    }

    public static void logRequestEvent(Map<String, Object> event) {
        LOGGER.info(toJson(event));
    }
}

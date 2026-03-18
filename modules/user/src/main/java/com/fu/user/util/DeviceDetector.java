package com.fu.user.util;

import com.fu.user.dto.auth.DeviceInfo;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for parsing User-Agent strings and detecting device information.
 * Supports both Mobile App (custom User-Agent) and Web Browser detection.
 */
@Slf4j
@UtilityClass
public class DeviceDetector {

    // Custom User-Agent format for mobile app: "SatoriApp/2.1.0 (iOS 17.2; iPhone 15)"
    private static final Pattern APP_USER_AGENT_PATTERN = 
            Pattern.compile("SatoriApp/([\\d.]+)\\s*\\(([^;]+);\\s*([^)]+)\\)");

    // Browser patterns
    private static final Pattern CHROME_PATTERN = Pattern.compile("Chrome/([\\d.]+)");
    private static final Pattern FIREFOX_PATTERN = Pattern.compile("Firefox/([\\d.]+)");
    private static final Pattern SAFARI_PATTERN = Pattern.compile("Version/([\\d.]+).*Safari");
    private static final Pattern EDGE_PATTERN = Pattern.compile("Edg/([\\d.]+)");
    private static final Pattern OPERA_PATTERN = Pattern.compile("OPR/([\\d.]+)");

    // OS patterns
    private static final Pattern WINDOWS_PATTERN = Pattern.compile("Windows NT ([\\d.]+)");
    private static final Pattern MAC_PATTERN = Pattern.compile("Mac OS X ([\\d_]+)");
    private static final Pattern LINUX_PATTERN = Pattern.compile("Linux");
    private static final Pattern ANDROID_PATTERN = Pattern.compile("Android ([\\d.]+)");
    private static final Pattern IOS_PATTERN = Pattern.compile("iPhone OS ([\\d_]+)|iPad.*OS ([\\d_]+)");

    // Mobile detection
    private static final Pattern MOBILE_PATTERN = 
            Pattern.compile("Mobile|Android|iPhone|iPad|iPod|webOS|BlackBerry|IEMobile|Opera Mini", 
                    Pattern.CASE_INSENSITIVE);

    /**
     * Platform types for session categorization.
     */
    public enum Platform {
        APP,    // Mobile application (iOS/Android)
        WEB     // Web browser (Desktop/Mobile)
    }

    /**
     * Parse User-Agent string and optional DeviceInfo from client.
     * Mobile apps should send DeviceInfo in request body for accurate tracking.
     * Web browsers rely on User-Agent parsing.
     *
     * @param userAgent User-Agent header from request
     * @param clientDeviceInfo Optional DeviceInfo sent by mobile app
     * @return Map containing device information for storage in JSONB
     */
    public Map<String, Object> detectDevice(String userAgent, DeviceInfo clientDeviceInfo) {
        // If client sent DeviceInfo (mobile app), use it with enrichment
        if (clientDeviceInfo != null && isAppDeviceInfo(clientDeviceInfo)) {
            return buildAppDeviceInfo(clientDeviceInfo);
        }

        // Check if User-Agent matches our mobile app pattern
        if (userAgent != null) {
            Matcher appMatcher = APP_USER_AGENT_PATTERN.matcher(userAgent);
            if (appMatcher.find()) {
                return buildAppDeviceInfoFromUserAgent(appMatcher);
            }
        }

        // Parse as web browser
        return parseWebUserAgent(userAgent);
    }

    /**
     * Check if DeviceInfo indicates a mobile app (has app_version or push_token).
     */
    private boolean isAppDeviceInfo(DeviceInfo deviceInfo) {
        return deviceInfo.getAppVersion() != null || 
               deviceInfo.getPushToken() != null ||
               "mobile".equalsIgnoreCase(deviceInfo.getDeviceType()) ||
               "tablet".equalsIgnoreCase(deviceInfo.getDeviceType());
    }

    /**
     * Build device info map from client-provided DeviceInfo (mobile app).
     */
    private Map<String, Object> buildAppDeviceInfo(DeviceInfo deviceInfo) {
        Map<String, Object> info = new HashMap<>();
        info.put("platform", "app");
        
        if (deviceInfo.getDeviceType() != null) {
            info.put("device_type", deviceInfo.getDeviceType().toLowerCase());
        } else {
            info.put("device_type", "mobile");
        }
        
        if (deviceInfo.getOs() != null) {
            info.put("os", deviceInfo.getOs());
        }
        if (deviceInfo.getAppVersion() != null) {
            info.put("app_version", deviceInfo.getAppVersion());
        }
        if (deviceInfo.getDeviceModel() != null) {
            info.put("device_model", deviceInfo.getDeviceModel());
        }
        if (deviceInfo.getPushToken() != null) {
            info.put("push_token", deviceInfo.getPushToken());
        }
        
        return info;
    }

    /**
     * Build device info from custom app User-Agent.
     * Format: "SatoriApp/2.1.0 (iOS 17.2; iPhone 15)"
     */
    private Map<String, Object> buildAppDeviceInfoFromUserAgent(Matcher matcher) {
        Map<String, Object> info = new HashMap<>();
        info.put("platform", "app");
        info.put("device_type", "mobile");
        info.put("app_version", matcher.group(1));
        info.put("os", matcher.group(2).trim());
        info.put("device_model", matcher.group(3).trim());
        return info;
    }

    /**
     * Parse web browser User-Agent string.
     */
    private Map<String, Object> parseWebUserAgent(String userAgent) {
        Map<String, Object> info = new HashMap<>();
        info.put("platform", "web");

        if (userAgent == null || userAgent.isBlank()) {
            info.put("device_type", "unknown");
            return info;
        }

        // Detect if mobile browser
        boolean isMobile = MOBILE_PATTERN.matcher(userAgent).find();
        info.put("device_type", isMobile ? "mobile" : "desktop");

        // Detect OS
        String os = detectOS(userAgent);
        if (os != null) {
            info.put("os", os);
        }

        // Detect Browser
        BrowserInfo browser = detectBrowser(userAgent);
        if (browser != null) {
            info.put("browser", browser.name);
            info.put("browser_version", browser.version);
        }

        return info;
    }

    /**
     * Detect operating system from User-Agent.
     */
    private String detectOS(String userAgent) {
        // Check Android first (contains "Linux")
        Matcher androidMatcher = ANDROID_PATTERN.matcher(userAgent);
        if (androidMatcher.find()) {
            return "Android " + androidMatcher.group(1);
        }

        // Check iOS
        Matcher iosMatcher = IOS_PATTERN.matcher(userAgent);
        if (iosMatcher.find()) {
            String version = iosMatcher.group(1) != null ? iosMatcher.group(1) : iosMatcher.group(2);
            if (version != null) {
                version = version.replace("_", ".");
            }
            return userAgent.contains("iPad") ? "iPadOS " + version : "iOS " + version;
        }

        // Check Windows
        Matcher windowsMatcher = WINDOWS_PATTERN.matcher(userAgent);
        if (windowsMatcher.find()) {
            return mapWindowsVersion(windowsMatcher.group(1));
        }

        // Check macOS
        Matcher macMatcher = MAC_PATTERN.matcher(userAgent);
        if (macMatcher.find()) {
            return "macOS " + macMatcher.group(1).replace("_", ".");
        }

        // Check Linux
        if (LINUX_PATTERN.matcher(userAgent).find()) {
            return "Linux";
        }

        return null;
    }

    /**
     * Map Windows NT version to friendly name.
     */
    private String mapWindowsVersion(String ntVersion) {
        return switch (ntVersion) {
            case "10.0" -> "Windows 10/11";
            case "6.3" -> "Windows 8.1";
            case "6.2" -> "Windows 8";
            case "6.1" -> "Windows 7";
            case "6.0" -> "Windows Vista";
            case "5.1", "5.2" -> "Windows XP";
            default -> "Windows";
        };
    }

    /**
     * Detect browser from User-Agent.
     */
    private BrowserInfo detectBrowser(String userAgent) {
        // Order matters: check Edge before Chrome (Edge contains "Chrome")
        
        Matcher edgeMatcher = EDGE_PATTERN.matcher(userAgent);
        if (edgeMatcher.find()) {
            return new BrowserInfo("Microsoft Edge", edgeMatcher.group(1));
        }

        Matcher operaMatcher = OPERA_PATTERN.matcher(userAgent);
        if (operaMatcher.find()) {
            return new BrowserInfo("Opera", operaMatcher.group(1));
        }

        Matcher chromeMatcher = CHROME_PATTERN.matcher(userAgent);
        if (chromeMatcher.find()) {
            return new BrowserInfo("Chrome", chromeMatcher.group(1));
        }

        Matcher firefoxMatcher = FIREFOX_PATTERN.matcher(userAgent);
        if (firefoxMatcher.find()) {
            return new BrowserInfo("Firefox", firefoxMatcher.group(1));
        }

        Matcher safariMatcher = SAFARI_PATTERN.matcher(userAgent);
        if (safariMatcher.find()) {
            return new BrowserInfo("Safari", safariMatcher.group(1));
        }

        return null;
    }

    /**
     * Convert DeviceInfo DTO to Map for storage.
     */
    public Map<String, Object> toMap(DeviceInfo deviceInfo) {
        if (deviceInfo == null) {
            return null;
        }
        return buildAppDeviceInfo(deviceInfo);
    }

    /**
     * Convert Map from storage to DeviceInfo DTO.
     */
    public DeviceInfo fromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        return DeviceInfo.builder()
                .deviceType((String) map.get("device_type"))
                .os((String) map.get("os"))
                .browser((String) map.get("browser"))
                .appVersion((String) map.get("app_version"))
                .deviceModel((String) map.get("device_model"))
                .pushToken((String) map.get("push_token"))
                .build();
    }

    /**
     * Get a human-readable description of the device.
     * Used for displaying session info to users.
     */
    public String getDeviceDescription(Map<String, Object> deviceInfo) {
        if (deviceInfo == null || deviceInfo.isEmpty()) {
            return "Unknown device";
        }

        String platform = (String) deviceInfo.get("platform");
        
        if ("app".equals(platform)) {
            String model = (String) deviceInfo.get("device_model");
            String os = (String) deviceInfo.get("os");
            String appVersion = (String) deviceInfo.get("app_version");
            
            StringBuilder desc = new StringBuilder();
            if (model != null) desc.append(model);
            if (os != null) {
                if (desc.length() > 0) desc.append(" (");
                desc.append(os);
                if (model != null) desc.append(")");
            }
            if (appVersion != null) {
                desc.append(" - App v").append(appVersion);
            }
            return desc.length() > 0 ? desc.toString() : "Mobile App";
        } else {
            String browser = (String) deviceInfo.get("browser");
            String os = (String) deviceInfo.get("os");
            String deviceType = (String) deviceInfo.get("device_type");
            
            StringBuilder desc = new StringBuilder();
            if (browser != null) desc.append(browser);
            if (os != null) {
                if (desc.length() > 0) desc.append(" on ");
                desc.append(os);
            }
            if ("mobile".equals(deviceType) && desc.length() > 0) {
                desc.append(" (Mobile)");
            }
            return desc.length() > 0 ? desc.toString() : "Web Browser";
        }
    }

    /**
     * Simple record for browser info.
     */
    private record BrowserInfo(String name, String version) {}
}

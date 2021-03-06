/*
 * Copyright 2004 - 2008 Christian Sprajc All rights reserved.
 *
 * This file is part of PowerFolder.
 *
 * PowerFolder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation.
 *
 * PowerFolder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PowerFolder. If not, see <http://www.gnu.org/licenses/>.
 *
 * $Id: AddressRange.java 4282 2008-06-16 03:25:09Z tot $
 */
package de.dal33t.powerfolder.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.dal33t.powerfolder.ConfigurationEntry;
import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.util.*;

/**
 * Helper to set/load the general HTTP proxy settings of this VM.
 *
 * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
 * @version $Revision: 1.14 $
 */
public class HTTPProxySettings {
    private static final Logger LOG = Logger.getLogger(HTTPProxySettings.class
        .getName());

    private static boolean SYSTEM_PROXY_ENABLED = false;

    static {
        if ("true".equalsIgnoreCase(System.getProperty("java.net.useSystemProxies"))) {
            SYSTEM_PROXY_ENABLED = StringUtils.isNotBlank(System.getProperty("http.proxyHost", ""));
        }
    }

    private HTTPProxySettings() {
        System.setProperty("java.net.useSystemProxies", "true");
    }

    private static void setProxyProperties(String proxyHost, int proxyPort, String nonProxyHosts) {
        if (StringUtils.isNotBlank(proxyHost)) {
            System.setProperty("http.proxyHost", proxyHost);
            System.setProperty("http.proxyPort", "" + proxyPort);
            System.setProperty("https.proxyHost", proxyHost);
            System.setProperty("https.proxyPort", "" + proxyPort);
        } else {
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            System.clearProperty("http.nonProxyHosts");
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");
            System.clearProperty("https.nonProxyHosts");
        }
        if (StringUtils.isNotBlank(nonProxyHosts)) {
            System.setProperty("http.nonProxyHosts", nonProxyHosts);
            System.setProperty("https.nonProxyHosts", "" + nonProxyHosts);
        } else {
            System.clearProperty("http.nonProxyHosts");
            System.clearProperty("https.nonProxyHosts");
        }
    }

    private static void setCredentials(final String proxyUsername,
        final String proxyPassword)
    {
        if (StringUtils.isBlank(proxyUsername)) {
            Authenticator.setDefault(null);
        } else {
            Reject.ifBlank(proxyPassword, "Password is blank");
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(proxyUsername,
                        proxyPassword.toCharArray());
                }
            });
        }
    }

    public static void loadFromConfig(Controller controller) {
        Reject.ifNull(controller, "Controller is null");

        String proxyHost = ConfigurationEntry.HTTP_PROXY_HOST
            .getValue(controller);
        int proxyPort = ConfigurationEntry.HTTP_PROXY_PORT
            .getValueInt(controller);
        String nonProxyHosts = ConfigurationEntry.HTTP_PROXY_NON_PROXY_HOSTS.getValue(controller);
        setProxyProperties(proxyHost, proxyPort, nonProxyHosts);

        // Username / Password
        String proxyUsername = ConfigurationEntry.HTTP_PROXY_USERNAME
            .getValue(controller);
        String proxyPassword = Util.toString(LoginUtil.deobfuscate(
            ConfigurationEntry.HTTP_PROXY_PASSWORD.getValue(controller)));
        try {
            setCredentials(proxyUsername, proxyPassword);
        } catch (IllegalArgumentException iae) {
            LOG.info("Could not set credentials for http proxy: " + iae.getMessage());
        }

        if (LOG.isLoggable(Level.WARNING)) {
            String auth = StringUtils.isBlank(proxyUsername)
                ? ""
                : "(" + proxyUsername + "/"
                    + (proxyPassword != null ? proxyPassword.length() : " n/a")
                    + " chars)";
            LOG.fine("Loaded HTTP proxy settings: " + proxyHost + ":"
                + proxyPort + " " + auth);
        }
    }

    public static void saveToConfig(Controller controller, String proxyHost,
        int proxyPort, String proxyUsername, String proxyPassword, String nonProxyHosts)
    {
        Reject.ifNull(controller, "Controller is null");
        if (StringUtils.isNotBlank(proxyHost)) {
            ConfigurationEntry.HTTP_PROXY_HOST.setValue(controller, proxyHost);
        } else {
            ConfigurationEntry.HTTP_PROXY_HOST.removeValue(controller);
        }
        if (proxyPort > 0) {
            ConfigurationEntry.HTTP_PROXY_PORT.setValue(controller,
                String.valueOf(proxyPort));
        } else {
            ConfigurationEntry.HTTP_PROXY_PORT.removeValue(controller);
        }
        if (StringUtils.isNotBlank(proxyUsername)) {
            ConfigurationEntry.HTTP_PROXY_USERNAME.setValue(controller,
                proxyUsername);
        } else {
            ConfigurationEntry.HTTP_PROXY_USERNAME.removeValue(controller);
        }
        if (StringUtils.isNotBlank(proxyPassword)) {
            ConfigurationEntry.HTTP_PROXY_PASSWORD.setValue(controller,
                LoginUtil.obfuscate(Util.toCharArray(proxyPassword)));
        } else {
            ConfigurationEntry.HTTP_PROXY_PASSWORD.removeValue(controller);
        }
        if (StringUtils.isNotBlank(nonProxyHosts)) {
            ConfigurationEntry.HTTP_PROXY_NON_PROXY_HOSTS.setValue(controller, nonProxyHosts);
        } else {
            ConfigurationEntry.HTTP_PROXY_NON_PROXY_HOSTS.removeValue(controller);
        }
        setProxyProperties(proxyHost, proxyPort, nonProxyHosts);
        setCredentials(proxyUsername, proxyPassword);

        if (StringUtils.isBlank(proxyHost)) {
            LOG.fine("Removed proxy settings");
        } else {
            String auth = StringUtils.isBlank(proxyUsername) ? "" : "("
                + proxyUsername + "/" + proxyPassword.length() + " chars)";
            LOG.fine("Saved HTTP proxy settings: " + proxyHost + ":"
                + proxyPort + " " + auth);
        }
    }

    public static final boolean useProxy(Controller c) {
        return StringUtils.isNotBlank(ConfigurationEntry.HTTP_PROXY_HOST.getValue(c));
    }

    public static final boolean requiresProxyAuthorization(Controller c) {
        String u;
        if (StringUtils.isNotBlank(c.getUpdateSettings().versionCheckURL)) {
            u = c.getUpdateSettings().versionCheckURL;
        } else
        if (ConfigurationEntry.SERVER_WEB_URL.hasNonBlankValue(c)) {
           u = ConfigurationEntry.SERVER_WEB_URL.getValue(c);
        } else if (ConfigurationEntry.SERVER_HTTP_TUNNEL_RPC_URL.hasNonBlankValue(c)) {
            u = ConfigurationEntry.SERVER_HTTP_TUNNEL_RPC_URL.getValue(c);
        } else {
            u = ConfigurationEntry.PROVIDER_URL.getValue(c);
        }
        InputStream in = null;
        try {
            URL testURL = new URL(u);
            URLConnection con = testURL.openConnection();
            con.setConnectTimeout(1000 * 5);
            con.setReadTimeout(1000 * 5);
            con.connect();
            in = con.getInputStream();
            in.close();
            return false;
        } catch (MalformedURLException e) {
            LOG.warning("Unable to test for proxy authorization: " + u + ". " + e.getMessage());
            return false;
        } catch (IOException e) {
            boolean proxyAuth =  (e.getMessage().contains("407") || e.getMessage().contains("Proxy Authorization Required"));
            if (proxyAuth) {
                LOG.warning("Proxy authorization required for: " + u + ". " + e.getMessage());
            } else {
                LOG.warning(u + ". " + e);
            }
            return proxyAuth;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception e) {
            }
        }
    }
}

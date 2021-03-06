/*
 * Copyright 2000-2013 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.client.communication;

import java.util.ArrayList;

import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.user.client.Command;
import com.vaadin.client.ApplicationConnection;
import com.vaadin.client.ResourceLoader;
import com.vaadin.client.ResourceLoader.ResourceLoadEvent;
import com.vaadin.client.ResourceLoader.ResourceLoadListener;
import com.vaadin.client.VConsole;
import com.vaadin.shared.ApplicationConstants;

/**
 * Represents the client-side endpoint of a bidirectional ("push") communication
 * channel. Can be used to send UIDL request messages to the server and to
 * receive UIDL messages from the server (either asynchronously or as a response
 * to a UIDL request.) Delegates the UIDL handling to the
 * {@link ApplicationConnection}.
 * 
 * @author Vaadin Ltd
 * @since 7.1
 */
public class PushConnection {

    protected enum State {
        /**
         * Connection is newly created and has not yet been started.
         */
        NEW,

        /**
         * Opening request has been sent, but still waiting for confirmation
         */
        CONNECT_PENDING,

        /**
         * Connection is open and ready to use.
         */
        CONNECTED,

        /**
         * Connection was disconnected while the connection was pending. Wait
         * for the connection to get established before closing it. No new
         * messages are accepted, but pending messages will still be delivered.
         */
        DISCONNECT_PENDING,

        /**
         * Connection has been disconnected and should not be used any more.
         */
        DISCONNECTED;
    }

    private ApplicationConnection connection;

    private JavaScriptObject socket;

    private ArrayList<String> messageQueue = new ArrayList<String>();

    private State state = State.NEW;

    private AtmosphereConfiguration config;

    private String uri;

    public PushConnection() {
    }

    /**
     * Two-phase construction to allow using GWT.create()
     * 
     * @param connection
     *            The ApplicationConnection
     */
    public void init(ApplicationConnection connection) {
        this.connection = connection;
    }

    public void connect(String uri) {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "Connection has already been connected.");
        }

        state = State.CONNECT_PENDING;
        // uri is needed to identify the right connection when closing
        this.uri = uri;
        VConsole.log("Establishing push connection");
        socket = doConnect(uri, getConfig());
    }

    public void push(String message) {
        switch (state) {
        case CONNECT_PENDING:
            VConsole.log("Queuing push message: " + message);
            messageQueue.add(message);
            break;
        case CONNECTED:
            VConsole.log("Sending push message: " + message);
            doPush(socket, message);
            break;
        case NEW:
            throw new IllegalStateException("Can not push before connecting");
        case DISCONNECT_PENDING:
        case DISCONNECTED:
            throw new IllegalStateException("Can not push after disconnecting");
        }
    }

    protected AtmosphereConfiguration getConfig() {
        if (config == null) {
            config = createConfig();
        }
        return config;
    }

    protected void onOpen(AtmosphereResponse response) {
        VConsole.log("Push connection established using "
                + response.getTransport());
        for (String message : messageQueue) {
            doPush(socket, message);
        }
        messageQueue.clear();

        switch (state) {
        case CONNECT_PENDING:
            state = State.CONNECTED;
            break;
        case DISCONNECT_PENDING:
            // Set state to connected to make disconnect close the connection
            state = State.CONNECTED;
            disconnect();
            break;
        case CONNECTED:
            // IE likes to open the same connection multiple times, just ignore
            break;
        default:
            throw new IllegalStateException(
                    "Got onOpen event when conncetion state is " + state
                            + ". This should never happen.");
        }
    }

    /**
     * Closes the push connection.
     */
    public void disconnect() {
        switch (state) {
        case NEW:
            // Nothing to close up, just update state
            state = State.DISCONNECTED;
            break;
        case CONNECT_PENDING:
            // Wait until connection is established before closing it
            state = State.DISCONNECT_PENDING;
            break;
        case CONNECTED:
            // Normal disconnect
            VConsole.log("Closing push connection");
            doDisconnect(uri);
            state = State.DISCONNECTED;
            break;
        case DISCONNECT_PENDING:
        case DISCONNECTED:
            // Nothing more to do
            break;
        }
    }

    protected void onMessage(AtmosphereResponse response) {
        String message = response.getResponseBody();
        if (message.startsWith("for(;;);")) {
            VConsole.log("Received push message: " + message);
            // "for(;;);[{json}]" -> "{json}"
            message = message.substring(9, message.length() - 1);
            connection.handlePushMessage(message);
        }
    }

    /**
     * Called if the transport mechanism cannot be used and the fallback will be
     * tried
     */
    protected void onTransportFailure() {
        VConsole.log("Push connection using primary method ("
                + getConfig().getTransport() + ") failed. Trying with "
                + getConfig().getFallbackTransport());
    }

    /**
     * Called if the push connection fails. Atmosphere will automatically retry
     * the connection until successful.
     * 
     */
    protected void onError() {
        VConsole.error("Push connection using "
                + getConfig().getTransport()
                + " failed!");
    }

    public static abstract class AbstractJSO extends JavaScriptObject {
        protected AbstractJSO() {

        }

        protected final native String getStringValue(String key)
        /*-{
           return this[key];
         }-*/;

        protected final native void setStringValue(String key, String value)
        /*-{
            this[key] = value;
        }-*/;

        protected final native int getIntValue(String key)
        /*-{
           return this[key];
         }-*/;

        protected final native void setIntValue(String key, int value)
        /*-{
            this[key] = value;
        }-*/;

    }

    public static class AtmosphereConfiguration extends AbstractJSO {

        protected AtmosphereConfiguration() {
            super();
        }

        public final String getTransport() {
            return getStringValue("transport");
        }

        public final String getFallbackTransport() {
            return getStringValue("fallbackTransport");
        }

        public final void setTransport(String transport) {
            setStringValue("transport", transport);
        }

        public final void setFallbackTransport(String fallbackTransport) {
            setStringValue("fallbackTransport", fallbackTransport);
        }
    }

    public static class AtmosphereResponse extends AbstractJSO {

        protected AtmosphereResponse() {

        }

        public final String getResponseBody() {
            return getStringValue("responseBody");
        }

        public final String getState() {
            return getStringValue("state");
        }

        public final String getError() {
            return getStringValue("error");
        }

        public final String getTransport() {
            return getStringValue("transport");
        }

    }

    protected native AtmosphereConfiguration createConfig()
    /*-{
        return {
            transport: 'websocket',
            fallbackTransport: 'streaming',
            contentType: 'application/json; charset=UTF-8',
            reconnectInterval: '5000',
            trackMessageLength: true 
        };
    }-*/;

    private native JavaScriptObject doConnect(String uri,
            JavaScriptObject config)
    /*-{
        var self = this;

        config.url = uri;
        config.onOpen = $entry(function(response) {
            self.@com.vaadin.client.communication.PushConnection::onOpen(*)(response);
        });
        config.onMessage = $entry(function(response) {
            self.@com.vaadin.client.communication.PushConnection::onMessage(*)(response);
        });
        config.onError = $entry(function(response) {
            self.@com.vaadin.client.communication.PushConnection::onError()(response);
        });
        config.onTransportFailure = $entry(function(reason,request) {
            self.@com.vaadin.client.communication.PushConnection::onTransportFailure(*)(reason);
        });

        return $wnd.jQueryVaadin.atmosphere.subscribe(config);
    }-*/;

    private native void doPush(JavaScriptObject socket, String message)
    /*-{
       socket.push(message);
    }-*/;

    private static native void doDisconnect(String url)
    /*-{
       $wnd.jQueryVaadin.atmosphere.unsubscribeUrl(url);
    }-*/;

    private static native boolean isAtmosphereLoaded()
    /*-{
        return $wnd.jQueryVaadin != undefined;  
    }-*/;

    /**
     * Runs the provided command when the Atmosphere javascript has been loaded.
     * If the script has already been loaded, the command is run immediately.
     * 
     * @param command
     *            the command to run when Atmosphere has been loaded.
     */
    public void runWhenAtmosphereLoaded(final Command command) {
        assert command != null;

        if (isAtmosphereLoaded()) {
            command.execute();
        } else {
            VConsole.log("Loading " + ApplicationConstants.VAADIN_PUSH_JS);
            ResourceLoader.get().loadScript(
                    connection.getConfiguration().getVaadinDirUrl()
                            + ApplicationConstants.VAADIN_PUSH_JS,
                    new ResourceLoadListener() {
                        @Override
                        public void onLoad(ResourceLoadEvent event) {
                            VConsole.log(ApplicationConstants.VAADIN_PUSH_JS
                                    + " loaded");
                            command.execute();
                        }

                        @Override
                        public void onError(ResourceLoadEvent event) {
                            VConsole.log(event.getResourceUrl()
                                    + " could not be loaded. Push will not work.");
                        }
                    });
        }
    }
}

/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty.handler.codec.http2;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.TextHeaderProcessor;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;

/**
 * Translate header/data/priority HTTP/2 frame events into HTTP events.  Just as {@link InboundHttp2ToHttpAdapter}
 * may generate multiple {@link FullHttpMessage} objects per stream, this class is more likely to
 * generate multiple messages per stream because the chances of an HTTP/2 event happening outside
 * the header/data message flow is more likely.
 */
public final class InboundHttp2ToHttpPriorityAdapter extends InboundHttp2ToHttpAdapter {
    private final IntObjectMap<HttpHeaders> outOfMessageFlowHeaders;

    /**
     * Creates a new instance
     *
     * @param connection The object which will provide connection notification events for the current connection
     * @param maxContentLength the maximum length of the message content. If the length of the message content exceeds
     *        this value, a {@link TooLongFrameException} will be raised.
     * @throws NullPointerException If {@code connection} is null
     * @throws IllegalArgumentException If {@code maxContentLength} is less than or equal to {@code 0}
     */
    public static InboundHttp2ToHttpPriorityAdapter newInstance(Http2Connection connection, int maxContentLength) {
        InboundHttp2ToHttpPriorityAdapter instance = new InboundHttp2ToHttpPriorityAdapter(connection,
                        maxContentLength);
        connection.addListener(instance);
        return instance;
    }

    /**
     * Creates a new instance
     *
     * @param connection The object which will provide connection notification events for the current connection
     * @param maxContentLength the maximum length of the message content. If the length of the message content exceeds
     *        this value, a {@link TooLongFrameException} will be raised.
     * @param validateHttpHeaders
     * <ul>
     * <li>{@code true} to validate HTTP headers in the http-codec</li>
     * <li>{@code false} not to validate HTTP headers in the http-codec</li>
     * </ul>
     * @throws NullPointerException If {@code connection} is null
     * @throws IllegalArgumentException If {@code maxContentLength} is less than or equal to {@code 0}
     */
    public static InboundHttp2ToHttpPriorityAdapter newInstance(Http2Connection connection, int maxContentLength,
                    boolean validateHttpHeaders) {
        InboundHttp2ToHttpPriorityAdapter instance = new InboundHttp2ToHttpPriorityAdapter(connection,
                        maxContentLength, validateHttpHeaders);
        connection.addListener(instance);
        return instance;
    }

    /**
     * Creates a new instance
     *
     * @param connection The object which will provide connection notification events for the current connection
     * @param maxContentLength the maximum length of the message content. If the length of the message content exceeds
     *        this value, a {@link TooLongFrameException} will be raised.
     * @throws NullPointerException If {@code connection} is null
     * @throws IllegalArgumentException If {@code maxContentLength} is less than or equal to {@code 0}
     */
    private InboundHttp2ToHttpPriorityAdapter(Http2Connection connection, int maxContentLength) {
        super(connection, maxContentLength);
        outOfMessageFlowHeaders = new IntObjectHashMap<HttpHeaders>();
    }

    /**
     * Creates a new instance
     *
     * @param connection The object which will provide connection notification events for the current connection
     * @param maxContentLength the maximum length of the message content. If the length of the message content exceeds
     *        this value, a {@link TooLongFrameException} will be raised.
     * @param validateHttpHeaders
     * <ul>
     * <li>{@code true} to validate HTTP headers in the http-codec</li>
     * <li>{@code false} not to validate HTTP headers in the http-codec</li>
     * </ul>
     * @throws NullPointerException If {@code connection} is null
     * @throws IllegalArgumentException If {@code maxContentLength} is less than or equal to {@code 0}
     */
    private InboundHttp2ToHttpPriorityAdapter(Http2Connection connection, int maxContentLength,
                    boolean validateHttpHeaders) {
        super(connection, maxContentLength, validateHttpHeaders);
        outOfMessageFlowHeaders = new IntObjectHashMap<HttpHeaders>();
    }

    @Override
    protected void removeMessage(int streamId) {
        super.removeMessage(streamId);
        outOfMessageFlowHeaders.remove(streamId);
    }

    /**
     * Get either the header or the trailing headers depending on which is valid to add to
     * @param msg The message containing the headers and trailing headers
     * @return The headers object which can be appended to or modified
     */
    private HttpHeaders getActiveHeaders(FullHttpMessage msg) {
        return msg.content().isReadable() ? msg.trailingHeaders() : msg.headers();
    }

    /**
     * This method will add the {@code headers} to the out of order headers map
     * @param streamId The stream id associated with {@code headers}
     * @param headers Newly encountered out of order headers which must be stored for future use
     */
    private void importOutOfMessageFlowHeaders(int streamId, HttpHeaders headers) {
        final HttpHeaders outOfMessageFlowHeader = outOfMessageFlowHeaders.get(streamId);
        if (outOfMessageFlowHeader == null) {
            outOfMessageFlowHeaders.put(streamId, headers);
        } else {
            outOfMessageFlowHeader.setAll(headers);
        }
    }

    /**
     * Take any saved out of order headers and export them to {@code headers}
     * @param streamId The stream id to search for out of order headers for
     * @param headers If any out of order headers exist for {@code streamId} they will be added to this object
     */
    private void exportOutOfMessageFlowHeaders(int streamId, final HttpHeaders headers) {
        final HttpHeaders outOfMessageFlowHeader = outOfMessageFlowHeaders.get(streamId);
        if (outOfMessageFlowHeader != null) {
            headers.setAll(outOfMessageFlowHeader);
        }
    }

    /**
     * This will remove all headers which are related to priority tree events
     * @param headers The headers to remove the priority tree elements from
     */
    private void removePriorityRelatedHeaders(HttpHeaders headers) {
        headers.remove(HttpUtil.ExtensionHeaders.Names.STREAM_DEPENDENCY_ID);
        headers.remove(HttpUtil.ExtensionHeaders.Names.STREAM_WEIGHT);
    }

    /**
     * Initializes the pseudo header fields for out of message flow HTTP/2 headers
     * @param builder The builder to set the pseudo header values
     */
    private void initializePseudoHeaders(DefaultHttp2Headers.Builder builder) {
        if (connection.isServer()) {
            builder.method(HttpUtil.OUT_OF_MESSAGE_SEQUENCE_METHOD.toString())
                   .path(HttpUtil.OUT_OF_MESSAGE_SEQUENCE_PATH);
        } else {
            builder.status(HttpUtil.OUT_OF_MESSAGE_SEQUENCE_RETURN_CODE.toString());
        }
    }

    /**
     * Add all the HTTP headers into the HTTP/2 headers {@code builder} object
     * @param headers The HTTP headers to translate to HTTP/2
     * @param builder The container for the HTTP/2 headers
     */
    private void addHttpHeadersToHttp2Headers(HttpHeaders headers, final DefaultHttp2Headers.Builder builder) {
        headers.forEachEntry(new TextHeaderProcessor() {
            @Override
            public boolean process(CharSequence name, CharSequence value) throws Exception {
                builder.add(name, value);
                return true;
            }
        });
    }

    @Override
    protected void fireChannelRead(ChannelHandlerContext ctx, FullHttpMessage msg, int streamId) {
        exportOutOfMessageFlowHeaders(streamId, getActiveHeaders(msg));
        super.fireChannelRead(ctx, msg, streamId);
    }

    @Override
    protected FullHttpMessage processHeadersBegin(ChannelHandlerContext ctx, int streamId, Http2Headers headers,
            boolean endOfStream, boolean allowAppend, boolean appendToTrailer) throws Http2Exception {
        FullHttpMessage msg = super.processHeadersBegin(ctx, streamId, headers,
                endOfStream, allowAppend, appendToTrailer);
        if (msg != null) {
            exportOutOfMessageFlowHeaders(streamId, getActiveHeaders(msg));
        }
        return msg;
    }

    @Override
    public void priorityTreeParentChanged(Http2Stream stream, Http2Stream oldParent) {
        Http2Stream parent = stream.parent();
        FullHttpMessage msg = messageMap.get(stream.id());
        if (msg == null) {
            // msg may be null if a HTTP/2 frame event in received outside the HTTP message flow
            // For example a PRIORITY frame can be received in any state besides IDLE
            // and the HTTP message flow exists in OPEN.
            if (parent != null && !parent.equals(connection.connectionStream())) {
                HttpHeaders headers = new DefaultHttpHeaders();
                headers.set(HttpUtil.ExtensionHeaders.Names.STREAM_DEPENDENCY_ID, parent.id());
                importOutOfMessageFlowHeaders(stream.id(), headers);
            }
        } else {
            if (parent == null) {
                removePriorityRelatedHeaders(msg.headers());
                removePriorityRelatedHeaders(msg.trailingHeaders());
            } else if (!parent.equals(connection.connectionStream())) {
                HttpHeaders headers = getActiveHeaders(msg);
                headers.set(HttpUtil.ExtensionHeaders.Names.STREAM_DEPENDENCY_ID, parent.id());
            }
        }
    }

    @Override
    public void onWeightChanged(Http2Stream stream, short oldWeight) {
        FullHttpMessage msg = messageMap.get(stream.id());
        HttpHeaders headers = null;
        if (msg == null) {
            // msg may be null if a HTTP/2 frame event in received outside the HTTP message flow
            // For example a PRIORITY frame can be received in any state besides IDLE
            // and the HTTP message flow exists in OPEN.
            headers = new DefaultHttpHeaders();
            importOutOfMessageFlowHeaders(stream.id(), headers);
        } else {
            headers = getActiveHeaders(msg);
        }
        headers.set(HttpUtil.ExtensionHeaders.Names.STREAM_WEIGHT, stream.weight());
    }

    @Override
    public void onPriorityRead(ChannelHandlerContext ctx, int streamId, int streamDependency, short weight,
                    boolean exclusive) throws Http2Exception {
        FullHttpMessage msg = messageMap.get(streamId);
        if (msg == null) {
            HttpHeaders headers = outOfMessageFlowHeaders.remove(streamId);
            if (headers == null) {
                throw Http2Exception.protocolError("Priority Frame recieved for unknown stream id %d", streamId);
            }

            DefaultHttp2Headers.Builder builder = DefaultHttp2Headers.newBuilder();
            initializePseudoHeaders(builder);
            addHttpHeadersToHttp2Headers(headers, builder);
            msg = newMessage(streamId, builder.build(), validateHttpHeaders);
            fireChannelRead(ctx, msg, streamId);
        }
    }
}

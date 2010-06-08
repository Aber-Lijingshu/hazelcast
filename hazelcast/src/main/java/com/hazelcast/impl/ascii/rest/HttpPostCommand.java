/* 
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.impl.ascii.rest;

import com.hazelcast.impl.ascii.NoOpCommand;
import com.hazelcast.nio.IOUtil;
import com.hazelcast.nio.ascii.SocketTextReader;

import java.nio.ByteBuffer;

public class HttpPostCommand extends HttpCommand {

    private ByteBuffer data = null;
    private ByteBuffer response = null;
    boolean nextLine = false;
    boolean readyToReadData = false;
    private ByteBuffer line = ByteBuffer.allocate(500);
    private String contentType = "text/html";
    private final SocketTextReader socketTextRequestReader;
    private boolean chunked = false;

    public HttpPostCommand(SocketTextReader socketTextRequestReader, String uri) {
        super(TextCommandType.HTTP_POST, uri);
        this.socketTextRequestReader = socketTextRequestReader;
    }

    /**
     * POST /path HTTP/1.0
     * User-Agent: HTTPTool/1.0
     * Content-TextCommandType: application/x-www-form-urlencoded
     * Content-Length: 45
     * <next_line>
     * <next_line>
     * byte[45]
     * <next_line>
     *
     * @param cb
     * @return
     */
    public boolean doRead(ByteBuffer cb) {
        boolean complete = doActualRead(cb);
        while (!complete && readyToReadData && chunked && cb.hasRemaining()) {
            complete = doActualRead(cb);
        }
        if (complete) {
            if (data != null) data.flip();
        }
        return complete;
    }

    public byte[] getData() {
        return (data == null) ? null : data.array();
    }

    public byte[] getContentType() {
        return (contentType == null) ? null : contentType.getBytes();
    }

    public boolean doActualRead(ByteBuffer cb) {
        if (readyToReadData) {
            if (chunked && (data == null || !data.hasRemaining())) {
                boolean hasLine = readLine(cb);
                String lineStr = null;
                if (hasLine) {
                    lineStr = toStringAndClear(line).trim();
                }
                if (hasLine) {
                    int dataSize = lineStr.length() == 0 ? 0 : Integer.parseInt(lineStr, 16); // hex string
                    if (dataSize == 0) {
                        return true;
                    }
                    if (data != null) {
                        ByteBuffer newData = ByteBuffer.allocate(data.capacity() + dataSize);
                        newData.put(data.array());
                        data = newData;
                    } else {
                        data = ByteBuffer.allocate(dataSize);
                    }
                }
            }
            IOUtil.copyToHeapBuffer(cb, data);
        }
        while (!readyToReadData && cb.hasRemaining()) {
            byte b = cb.get();
            char c = (char) b;
            if (c == '\n') {
                processLine(toStringAndClear(line));
                if (nextLine) {
                    readyToReadData = true;
                }
                nextLine = true;
            } else if (c != '\r') {
                nextLine = false;
                line.put(b);
            }
        }
        return !chunked && ((data != null) && !data.hasRemaining());
    }

    String toStringAndClear(ByteBuffer bb) {
        if (bb == null) return "";
        String result = null;
        if (bb.position() == 0) {
            result = "";
        } else {
            result = new String(bb.array(), 0, bb.position());
        }
        bb.clear();
        return result;
    }

    boolean readLine(ByteBuffer cb) {
        while (cb.hasRemaining()) {
            byte b = cb.get();
            char c = (char) b;
            if (c == '\n') {
                return true;
            } else if (c != '\r') {
                line.put(b);
            }
        }
        return false;
    }

    private void processLine(String currentLine) {
//        System.out.println("LINE " + line);
        if (contentType == null && currentLine.startsWith(HEADER_CONTENT_TYPE)) {
            contentType = currentLine.substring(currentLine.indexOf(' ') + 1);
        } else if (data == null && currentLine.startsWith(HEADER_CONTENT_LENGTH)) {
            data = ByteBuffer.allocate(Integer.parseInt(currentLine.substring(currentLine.indexOf(' ') + 1)));
        } else if (!chunked && currentLine.startsWith(HEADER_CHUNKED)) {
            chunked = true;
        } else if (currentLine.startsWith("Expect: 100")) {
            socketTextRequestReader.sendResponse(new NoOpCommand(RES_100));
        }
//        System.out.println(contentType + "  ........ " + data);
    }

    public void setResponse(byte[] value) {
        this.response = ByteBuffer.wrap(value);
    }

    public boolean writeTo(ByteBuffer bb) {
        while (bb.hasRemaining() && response.hasRemaining()) {
            bb.put(response.get());
        }
        return !response.hasRemaining();
    }
}
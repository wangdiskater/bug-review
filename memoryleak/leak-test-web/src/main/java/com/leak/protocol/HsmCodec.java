/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.leak.protocol;

import com.leak.protocol.common.BitOperator;
import io.netty.buffer.ByteBufUtil;
import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.codec.ExchangeCodec;
import org.apache.dubbo.rpc.RpcInvocation;

import java.io.IOException;

/**
 * hsm codec.
 *
 * <p>协议约定 *************************************************************************** Data Length *
 * header * command/resp code * error code * Data * 2-bytes * 8-bytes * 2-bytes * 1-byte * ~ *
 * ************************************************************************** Data Length ：header +
 * command/resp + [error] + Data 的长度 header ：可以为任何8字节的字符，此处约定为RequestId command/resp code ：命令/响应码
 * error code ：错误码，响应时才有 Data ：数据
 */
public class HsmCodec extends ExchangeCodec {

  public static final String NAME = "hsm";

  // 数据包长度标识的长度，2-bytes
  private static final int DATA_LENGTH_LENGTH = 2;
  // header length
  private static final int HEADER_LENGTH = 8;
  // command/resp code
  private static final int COMMAND_LENGTH = 2;
  // error_length
  // private static final int ERROR_LENGTH = 2;
  //
  private static final String RESPONSE_OK = "3030";

  private static final Logger log = LoggerFactory.getLogger(HsmCodec.class);

  @Override
  public void encode(Channel channel, ChannelBuffer buffer, Object message) throws IOException {
    if (message instanceof Request) {
      encodeRequest(channel, buffer, (Request) message);
    }
  }

  protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req)
      throws IOException {
    byte[] body = null;
    if (req.isHeartbeat()) {
      body = encodeEventData();
    } else {
      RpcInvocation inv = (RpcInvocation) req.getData();
      Object[] args = inv.getArguments();
      if (args == null || args.length < 1) throw new IllegalArgumentException("参数异常");
      if (args[0] instanceof byte[]) {
        body = (byte[]) args[0];
      } else {
        throw new IllegalArgumentException("参数异常");
      }
    }
    byte[] dst = new byte[(DATA_LENGTH_LENGTH + HEADER_LENGTH + body.length)];
    // header
    byte[] header = new byte[(DATA_LENGTH_LENGTH + HEADER_LENGTH)];
    // encode data length,2-bytes
    Bytes.short2bytes((short) (body.length + HEADER_LENGTH), dst);
    // encode request id,8-bytes
    BitOperator.long2AsciiBytes(req.getId(), dst, 2, 8);
    // body
    System.arraycopy(body, 0, dst, DATA_LENGTH_LENGTH + HEADER_LENGTH, body.length);

    log.debug("send hsm(" + channel.getRemoteAddress() + ") " + ByteBufUtil.hexDump(dst));

    int savedWriteIndex = buffer.writerIndex();
    buffer.writeBytes(dst);
    buffer.writerIndex(savedWriteIndex + dst.length);
  }

  public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
    // 解码数据包长度
    int readable = buffer.readableBytes();
    if (readable < DATA_LENGTH_LENGTH) {
      return DecodeResult.NEED_MORE_INPUT;
    }
    byte[] length = new byte[DATA_LENGTH_LENGTH];
    buffer.readBytes(length);
    int len = Bytes.bytes2short(length); // 获取长度
    // checkPayload(channel, len);
    if (readable < (len + DATA_LENGTH_LENGTH)) { // 包长度不够继续从端口获取
      return DecodeResult.NEED_MORE_INPUT;
    }
    byte[] body = new byte[len];
    buffer.readBytes(body);
    return decodeBody(channel, buffer, body);
  }

  protected Object decodeBody(Channel channel, ChannelBuffer buffer, byte[] body)
      throws IOException {
    // get request id
    long id = BitOperator.asciiBytes2Long(body, 0, 8);
    // decode response
    Response res = new Response(id);
    // 4E44属于心跳指令，且不由应用层服务发出
    if ("4e44".equals(ByteBufUtil.hexDump(body, 8, 2)) && getRequestData(id) == null) {
      res.setEvent(Response.HEARTBEAT_EVENT);
    }
    // 加密机返回的状态，直接返回给应用层处理
    // 处理body
    try {
      Object data;
      if (res.isEvent()) {
        data = decodeEventData(res, body);
      } else {
        int newLength = body.length - HEADER_LENGTH - COMMAND_LENGTH;
        byte[] dst = new byte[newLength > 0 ? newLength : 0];
        System.arraycopy(
            body, (HEADER_LENGTH + COMMAND_LENGTH), dst, 0, Math.min(body.length, dst.length));
        DecodeableRpcResult result;
        if (channel
            .getUrl()
            .getParameter(
                Constants.DECODE_IN_IO_THREAD_KEY, Constants.DEFAULT_DECODE_IN_IO_THREAD)) {
          result = new DecodeableRpcResult(res, dst);
          result.decode();
        } else {
          result = new DecodeableRpcResult(res, dst);
        }
        data = result;
      }
      res.setResult(data);
    } catch (Throwable t) {
      if (log.isWarnEnabled()) {
        log.warn("Decode response failed: " + t.getMessage(), t);
      }
      res.setStatus(Response.CLIENT_ERROR);
      res.setErrorMessage(StringUtils.toString(t));
    }
    return res;
  }

  protected Object decodeEventData(Response response, byte[] body) throws IOException {
    log.debug("heartbeat data ：" + ByteBufUtil.hexDump(body));
    if (RESPONSE_OK.equals(ByteBufUtil.hexDump(body, 10, 2))) {
      response.setStatus(Response.OK);
    } else {
      response.setStatus(Response.SERVICE_ERROR);
    }
    return null;
  }

  private byte[] encodeEventData() throws IOException {
    return ByteBufUtil.decodeHexDump("4E43");
  }
}

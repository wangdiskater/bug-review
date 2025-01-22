package com.leak.protocol;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.Decodeable;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.rpc.RpcResult;

import java.io.IOException;

public class DecodeableRpcResult extends RpcResult implements Codec2, Decodeable {

  private static final Logger log = LoggerFactory.getLogger(DecodeableRpcResult.class);

  private Response response;

  private byte[] body; // header + command + error code + body

  private volatile boolean hasDecoded;

  public DecodeableRpcResult(Response response, byte[] body) {
    Assert.notNull(response, "response == null");
    this.response = response;
    this.body = body;
  }

  @Override
  public void encode(Channel channel, ChannelBuffer buffer, Object message) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
    // Type[] returnType = RpcUtils.getReturnTypes(invocation)
    // setException(Throwable e)
    // setAttachments(Map<String, String> map)
    setValue(body);
    return this;
  }

  @Override
  public void decode() throws Exception {
    if (!hasDecoded) {
      try {
        decode(null, null);
      } catch (Throwable e) {
        if (log.isWarnEnabled()) {
          log.warn("Decode rpc result failed: " + e.getMessage(), e);
        }
        response.setStatus(Response.CLIENT_ERROR);
        response.setErrorMessage(StringUtils.toString(e));
      } finally {
        hasDecoded = true;
      }
    }
  }
}

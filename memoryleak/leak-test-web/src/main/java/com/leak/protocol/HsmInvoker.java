package com.leak.protocol;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.utils.AtomicPositiveInteger;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ResponseFuture;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.protocol.AbstractInvoker;
import org.apache.dubbo.rpc.protocol.dubbo.FutureAdapter;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public class HsmInvoker<T> extends AbstractInvoker<T> {

  private final ExchangeClient[] clients;

  private final AtomicPositiveInteger index = new AtomicPositiveInteger();

  private final String version;

  private final ReentrantLock destroyLock = new ReentrantLock();

  private final Set<Invoker<?>> invokers;

  public HsmInvoker(
      Class<T> serviceType, URL url, ExchangeClient[] clients, Set<Invoker<?>> invokers) {
    super(
        serviceType,
        url,
        new String[] {
          Constants.INTERFACE_KEY, Constants.GROUP_KEY, Constants.TOKEN_KEY, Constants.TIMEOUT_KEY
        });
    this.clients = clients;
    // get version.
    this.version = url.getParameter(Constants.VERSION_KEY, "0.0.0");
    this.invokers = invokers;
  }

  @Override
  protected Result doInvoke(Invocation invocation) throws Throwable {
    RpcInvocation inv = (RpcInvocation) invocation;
    final String methodName = RpcUtils.getMethodName(invocation);
    inv.setAttachment(Constants.PATH_KEY, getUrl().getPath());
    inv.setAttachment(Constants.VERSION_KEY, version);

    ExchangeClient currentClient;
    if (clients.length == 1) {
      currentClient = clients[0];
    } else {
      currentClient = clients[index.getAndIncrement() % clients.length];
    }
    try {
      boolean isAsync = RpcUtils.isAsync(getUrl(), invocation);
      boolean isOneway = RpcUtils.isOneway(getUrl(), invocation);
      int timeout =
          getUrl().getMethodParameter(methodName, Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
      if (isOneway) {
        boolean isSent = getUrl().getMethodParameter(methodName, Constants.SENT_KEY, false);
        currentClient.send(inv, isSent);
        RpcContext.getContext().setFuture(null);
        return new RpcResult();
      } else if (isAsync) {
        ResponseFuture future = currentClient.request(inv, timeout);
        RpcContext.getContext().setFuture(new FutureAdapter<Object>(future));
        return new RpcResult();
      } else {
        RpcContext.getContext().setFuture(null);
        return (Result) currentClient.request(inv, timeout).get();
      }
    } catch (TimeoutException e) {
      throw new RpcException(
          RpcException.TIMEOUT_EXCEPTION,
          "Invoke remote method timeout. method: "
              + invocation.getMethodName()
              + ", provider: "
              + getUrl()
              + ", cause: "
              + e.getMessage(),
          e);
    } catch (RemotingException e) {
      throw new RpcException(
          RpcException.NETWORK_EXCEPTION,
          "Failed to invoke remote method: "
              + invocation.getMethodName()
              + ", provider: "
              + getUrl()
              + ", cause: "
              + e.getMessage(),
          e);
    }
  }

  @Override
  public boolean isAvailable() {
    if (!super.isAvailable()) return false;
    for (ExchangeClient client : clients) {
      if (client.isConnected() && !client.hasAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY)) {
        // cannot write == not Available ?
        return true;
      }
    }
    return false;
  }

  @Override
  public void destroy() {
    // in order to avoid closing a client multiple times, a counter is used in case of connection
    // per jvm, every
    // time when client.close() is called, counter counts down once, and when counter reaches zero,
    // client will be
    // closed.
    if (super.isDestroyed()) {
      return;
    } else {
      // double check to avoid dup close
      destroyLock.lock();
      try {
        if (super.isDestroyed()) {
          return;
        }
        super.destroy();
        if (invokers != null) {
          invokers.remove(this);
        }
        for (ExchangeClient client : clients) {
          try {
            client.close(ConfigurationUtils.getServerShutdownTimeout());
          } catch (Throwable t) {
            //logger.warn(t.getMessage(), t);
          }
        }

      } finally {
        destroyLock.unlock();
      }
    }
  }
}

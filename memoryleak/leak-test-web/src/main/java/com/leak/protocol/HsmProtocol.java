package com.leak.protocol;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.exchange.*;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class HsmProtocol extends AbstractProtocol {

  public static final String NAME = "hsm";

  public static final int DEFAULT_PORT = 1818;
  private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
  private static HsmProtocol INSTANCE;

  /** <host:port,Exchanger> */
  private final Map<String, ExchangeServer> serverMap = new ConcurrentHashMap<>();
  /** <host:port,Exchanger> */
  private final Map<String, List<ReferenceCountExchangeClient>> referenceClientMap =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
  private final Set<String> optimizers = new ConcurrentHashSet<>();
  /** dubbo side export a stub key for dispatching event servicekey-stubmethods */
  private final ConcurrentMap<String, String> stubServiceMethodsMap = new ConcurrentHashMap<>();

  private ExchangeHandler requestHandler =
      new ExchangeHandlerAdapter() {

        @Override
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message)
            throws RemotingException {

          /*if (!(message instanceof Invocation)) {
              throw new RemotingException(channel, "Unsupported request: "
                      + (message == null ? null : (message.getClass().getName() + ": " + message))
                      + ", channel: dubbo: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
          }

          Invocation inv = (Invocation) message;
          Invoker<?> invoker = getInvoker(channel, inv);
          // need to consider backward-compatibility if it's a callback
          if (Boolean.TRUE.toString().equals(inv.getAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
              String methodsStr = invoker.getUrl().getParameters().get("methods");
              boolean hasMethod = false;
              if (methodsStr == null || !methodsStr.contains(",")) {
                  hasMethod = inv.getMethodName().equals(methodsStr);
              } else {
                  String[] methods = methodsStr.split(",");
                  for (String method : methods) {
                      if (inv.getMethodName().equals(method)) {
                          hasMethod = true;
                          break;
                      }
                  }
              }
              if (!hasMethod) {
                  logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                          + " not found in callback key interface ,invoke will be ignored."
                          + " please update the business interface. url is:"
                          + invoker.getUrl()) + " ,invocation is :" + inv);
                  return null;
              }
          }
          RpcContext rpcContext = RpcContext.getContext();
          rpcContext.setRemoteAddress(channel.getRemoteAddress());
          Result result = invoker.invoke(inv);

          if (result instanceof AsyncRpcResult) {
              return ((AsyncRpcResult) result).getResultFuture().thenApply(r -> (Object) r);

          } else {
              return CompletableFuture.completedFuture(result);
          }*/
          return null;
        }

        @Override
        public void received(Channel channel, Object message) throws RemotingException {
          if (message instanceof Invocation) {
            reply((ExchangeChannel) channel, message);

          } else {
            super.received(channel, message);
          }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
          invoke(channel, Constants.ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
          if (logger.isDebugEnabled()) {
            logger.debug(
                "disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
          }
          invoke(channel, Constants.ON_DISCONNECT_KEY);
        }

        private void invoke(Channel channel, String methodKey) {
          Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
          if (invocation != null) {
            try {
              received(channel, invocation);
            } catch (Throwable t) {
              logger.warn(
                  "Failed to invoke event method "
                      + invocation.getMethodName()
                      + "(), cause: "
                      + t.getMessage(),
                  t);
            }
          }
        }

        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
          String method = url.getParameter(methodKey);
          if (method == null || method.length() == 0) {
            return null;
          }

          RpcInvocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
          invocation.setAttachment(Constants.PATH_KEY, url.getPath());
          invocation.setAttachment(Constants.GROUP_KEY, url.getParameter(Constants.GROUP_KEY));
          invocation.setAttachment(
              Constants.INTERFACE_KEY, url.getParameter(Constants.INTERFACE_KEY));
          invocation.setAttachment(Constants.VERSION_KEY, url.getParameter(Constants.VERSION_KEY));
          if (url.getParameter(Constants.STUB_EVENT_KEY, false)) {
            invocation.setAttachment(Constants.STUB_EVENT_KEY, Boolean.TRUE.toString());
          }

          return invocation;
        }
      };

  public HsmProtocol() {
    this.INSTANCE = this;
  }

  public static HsmProtocol getHsmProtocol() {
    if (INSTANCE == null) {
      ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(HsmProtocol.NAME); // load
    }
    return INSTANCE;
  }

  public Collection<Invoker<?>> getInvokers() {
    return Collections.unmodifiableCollection(invokers);
  }

  @Override
  public int getDefaultPort() {
    return DEFAULT_PORT;
  }

  @Override
  public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
    return null;
  }

  /**
   * 引用远程服务：<br>
   * 1. 当用户调用refer()所返回的Invoker对象的invoke()方法时，协议需相应执行同URL远端export()传入的Invoker对象的invoke()方法。<br>
   * 2. refer()返回的Invoker由协议实现，协议通常需要在此Invoker中发送远程请求。<br>
   * 3. 当url中有设置check=false时，连接失败不能抛出异常，需内部自动恢复。<br>
   *
   * @param <T> 服务的类型
   * @param serviceType 服务的类型
   * @param url 远程服务的URL地址
   * @return invoker 服务的本地代理
   * @throws RpcException 当连接服务提供方失败时抛出
   */
  @Override
  public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
    // optimizeSerialization(url);
    // create rpc invoker.
    HsmInvoker<T> invoker = new HsmInvoker<T>(serviceType, url, getClients(url), invokers);
    invokers.add(invoker);
    return invoker;
  }

  private ExchangeClient[] getClients(URL url) {
    // whether to share connection

    boolean useShareConnect = false;

    int connections = url.getParameter(Constants.CONNECTIONS_KEY, 0);
    List<ReferenceCountExchangeClient> shareClients = null;
    // if not configured, connection is shared, otherwise, one connection for one key
    if (connections == 0) {
      useShareConnect = true;

      /** The xml configuration should have a higher priority than properties. */
      String shareConnectionsStr = url.getParameter(Constants.SHARE_CONNECTIONS_KEY, (String) null);
      connections =
          Integer.parseInt(
              StringUtils.isBlank(shareConnectionsStr)
                  ? ConfigUtils.getProperty(
                      Constants.SHARE_CONNECTIONS_KEY, Constants.DEFAULT_SHARE_CONNECTIONS)
                  : shareConnectionsStr);
      shareClients = getSharedClient(url, connections);
    }

    ExchangeClient[] clients = new ExchangeClient[connections];
    for (int i = 0; i < clients.length; i++) {
      if (useShareConnect) {
        clients[i] = shareClients.get(i);

      } else {
        clients[i] = initClient(url);
      }
    }

    return clients;
  }

  /**
   * Get shared connection
   *
   * @param url
   * @param connectNum connectNum must be greater than or equal to 1
   */
  private List<ReferenceCountExchangeClient> getSharedClient(URL url, int connectNum) {
    String key = url.getAddress();
    List<ReferenceCountExchangeClient> clients = referenceClientMap.get(key);

    if (checkClientCanUse(clients)) {
      batchClientRefIncr(clients);
      return clients;
    }

    locks.putIfAbsent(key, new Object());
    synchronized (locks.get(key)) {
      clients = referenceClientMap.get(key);
      // dubbo check
      if (checkClientCanUse(clients)) {
        batchClientRefIncr(clients);
        return clients;
      }

      // connectNum must be greater than or equal to 1
      connectNum = Math.max(connectNum, 1);

      // If the clients is empty, then the first initialization is
      if (CollectionUtils.isEmpty(clients)) {
        clients = buildReferenceCountExchangeClientList(url, connectNum);
        referenceClientMap.put(key, clients);

      } else {
        for (int i = 0; i < clients.size(); i++) {
          ReferenceCountExchangeClient referenceCountExchangeClient = clients.get(i);
          // If there is a client in the list that is no longer available, create a new one to
          // replace him.
          if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
            clients.set(i, buildReferenceCountExchangeClient(url));
            continue;
          }

          referenceCountExchangeClient.incrementAndGetCount();
        }
      }

      /**
       * I understand that the purpose of the remove operation here is to avoid the expired url key
       * always occupying this memory space.
       */
      locks.remove(key);

      return clients;
    }
  }

  /**
   * Check if the client list is all available
   *
   * @param referenceCountExchangeClients
   * @return true-available，false-unavailable
   */
  private boolean checkClientCanUse(
      List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
    if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
      return false;
    }

    for (ReferenceCountExchangeClient referenceCountExchangeClient :
        referenceCountExchangeClients) {
      // As long as one client is not available, you need to replace the unavailable client with the
      // available one.
      if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
        return false;
      }
    }

    return true;
  }

  /**
   * Add client references in bulk
   *
   * @param referenceCountExchangeClients
   */
  private void batchClientRefIncr(
      List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
    if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
      return;
    }

    for (ReferenceCountExchangeClient referenceCountExchangeClient :
        referenceCountExchangeClients) {
      if (referenceCountExchangeClient != null) {
        referenceCountExchangeClient.incrementAndGetCount();
      }
    }
  }

  /**
   * Bulk build client
   *
   * @param url
   * @param connectNum
   * @return
   */
  private List<ReferenceCountExchangeClient> buildReferenceCountExchangeClientList(
      URL url, int connectNum) {
    List<ReferenceCountExchangeClient> clients = new CopyOnWriteArrayList<>();

    for (int i = 0; i < connectNum; i++) {
      clients.add(buildReferenceCountExchangeClient(url));
    }

    return clients;
  }

  /**
   * Build a single client
   *
   * @param url
   * @return
   */
  private ReferenceCountExchangeClient buildReferenceCountExchangeClient(URL url) {
    ExchangeClient exchangeClient = initClient(url);

    return new ReferenceCountExchangeClient(exchangeClient);
  }

  /**
   * Create new connection
   *
   * @param url
   */
  private ExchangeClient initClient(URL url) {

    // client type setting.
    String str =
        url.getParameter(
            Constants.CLIENT_KEY,
            url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_CLIENT));

    url = url.addParameter(Constants.CODEC_KEY, HsmCodec.NAME);
    // enable heartbeat by default
    url =
        url.addParameterIfAbsent(
            Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));

    // BIO is not allowed since it has severe performance issue.
    if (str != null
        && str.length() > 0
        && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
      throw new RpcException(
          "Unsupported client type: "
              + str
              + ","
              + " supported client type is "
              + StringUtils.join(
                  ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(),
                  " "));
    }

    ExchangeClient client;
    try {
      // connection should be lazy
      if (url.getParameter(Constants.LAZY_CONNECT_KEY, false)) {
        client = new LazyConnectExchangeClient(url, requestHandler);

      } else {
        client = Exchangers.connect(url, requestHandler);
      }

    } catch (RemotingException e) {
      throw new RpcException(
          "Fail to create remoting client for key(" + url + "): " + e.getMessage(), e);
    }

    return client;
  }

  @Override
  public void destroy() {
    for (String key : new ArrayList<>(serverMap.keySet())) {
      ExchangeServer server = serverMap.remove(key);

      if (server == null) {
        continue;
      }

      try {
        if (logger.isInfoEnabled()) {
          logger.info("Close hsm server: " + server.getLocalAddress());
        }

        server.close(ConfigurationUtils.getServerShutdownTimeout());

      } catch (Throwable t) {
        logger.warn(t.getMessage(), t);
      }
    }

    for (String key : new ArrayList<>(referenceClientMap.keySet())) {
      List<ReferenceCountExchangeClient> clients = referenceClientMap.remove(key);

      if (CollectionUtils.isEmpty(clients)) {
        continue;
      }

      for (ReferenceCountExchangeClient client : clients) {
        closeReferenceCountExchangeClient(client);
      }
    }

    stubServiceMethodsMap.clear();
    super.destroy();
  }

  /**
   * close ReferenceCountExchangeClient
   *
   * @param client
   */
  private void closeReferenceCountExchangeClient(ReferenceCountExchangeClient client) {
    if (client == null) {
      return;
    }

    try {
      if (logger.isInfoEnabled()) {
        logger.info(
            "Close hsm connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
      }

      client.close(ConfigurationUtils.getServerShutdownTimeout());

      //
      /**
       * At this time, ReferenceCountExchangeClient#client has been replaced with
       * LazyConnectExchangeClient. Do you need to call client.close again to ensure that
       * LazyConnectExchangeClient is also closed?
       */
    } catch (Throwable t) {
      logger.warn(t.getMessage(), t);
    }
  }
}

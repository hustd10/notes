# 实例

例如如下一个proto：

    package echo;
    
    option java_generic_services = true;
    
    message EchoRequest {
        required string msg=1;
    }
    
    message EchoResponse {
        required string msg=1;
    }
    
    service EchoService {
        rpc Echo(EchoRequest) returns (EchoResponse);
    }

其中定义了一个RPC Service （EchoService），生成的Java代码中，EchoService被定义为一个继承自 com.google.protobuf.Service 的虚基类：

    public static abstract class EchoService
        implements com.google.protobuf.Service {

EchoService类中，包含两个接口：Interface 和 BlockingInterface：

    // Interface
    public interface Interface {
        /**
        * <code>rpc Echo(.echo.EchoRequest) returns (.echo.EchoResponse);</code>
        */
        public abstract void echo(
          com.google.protobuf.RpcController controller,
          echo.Echo.EchoRequest request,
          com.google.protobuf.RpcCallback<echo.Echo.EchoResponse> done);
    }

    // BlockingInterface
    public interface BlockingInterface {
        public echo.Echo.EchoResponse echo(
          com.google.protobuf.RpcController controller,
          echo.Echo.EchoRequest request)
          throws com.google.protobuf.ServiceException;
    }
    
Interface 和 BlockingInterface 分别表示异步和同步方式的RPC调用，客户端和服务端都应当实现相应的Interface实现RPC调用。

EchoService 有两个类 Stub 和 BlockingStub 分别实现了这两个接口：

    // Stub
    public static final class Stub extends echo.Echo.EchoService implements Interface {
      private Stub(com.google.protobuf.RpcChannel channel) {
        this.channel = channel;
      }

      private final com.google.protobuf.RpcChannel channel;

      public com.google.protobuf.RpcChannel getChannel() {
        return channel;
      }

      public  void echo(
          com.google.protobuf.RpcController controller,
          echo.Echo.EchoRequest request,
          com.google.protobuf.RpcCallback<echo.Echo.EchoResponse> done) {
        channel.callMethod(
          getDescriptor().getMethods().get(0),
          controller,
          request,
          echo.Echo.EchoResponse.getDefaultInstance(),
          com.google.protobuf.RpcUtil.generalizeCallback(
            done,
            echo.Echo.EchoResponse.class,
            echo.Echo.EchoResponse.getDefaultInstance()));
      }
    }
    
    // BlockingStub
    private static final class BlockingStub implements BlockingInterface {
      private BlockingStub(com.google.protobuf.BlockingRpcChannel channel) {
        this.channel = channel;
      }

      private final com.google.protobuf.BlockingRpcChannel channel;

      public echo.Echo.EchoResponse echo(
          com.google.protobuf.RpcController controller,
          echo.Echo.EchoRequest request)
          throws com.google.protobuf.ServiceException {
        return (echo.Echo.EchoResponse) channel.callBlockingMethod(
          getDescriptor().getMethods().get(0),
          controller,
          request,
          echo.Echo.EchoResponse.getDefaultInstance());
      }

    }
    
用户（客户端）通过Stub 和 BlockingStub 调用相应的RPC函数，可以看到，Stub实际调用channel来完成RPC调用，异步和同步分别对应 com.google.protobuf.RpcChannel 和 com.google.protobuf.BlockingRpcChannel，RpcChannel 和 BlockingRpcChannel 是两个接口（源码）：

    // RpcChannel
    public interface RpcChannel {
        void callMethod(Descriptors.MethodDescriptor method,
                RpcController controller,
                Message request,
                Message responsePrototype,
                RpcCallback<Message> done)
    }
    
    // BlockingRpcChannel
    public interface BlockingRpcChannel {
        Message callBlockingMethod(Descriptors.MethodDescriptor method,
                           RpcController controller,
                           Message request,
                           Message responsePrototype)
                    throws ServiceException
    }
    
用户应当实现 RpcChannel/BlockingRpcChannel，然后传递给 Stub/BlockingStub，用户调用 Stub 和 BlcokingStub ，不是直接调用构造函数初始化（可以看到上面的构造函数都是private的），EchoService 中有相应的初始化函数：

    public static Stub newStub(
        com.google.protobuf.RpcChannel channel) {
      return new Stub(channel);
    }
    
    public static BlockingInterface newBlockingStub(
        com.google.protobuf.BlockingRpcChannel channel) {
      return new BlockingStub(channel);
    }

用户通过这两个函数传递自己的 RpcChannel，获取相应的 Stub 实例，然后通过 Stub 发起RPC调用，过程如下：

    BlockingRpcChannel channel = rpcImpl.newChannel("remotehost.example.com:1234");
    RpcController controller = rpcImpl.newController();
    EchoService service = EchoService.newStub(channel);
    service.(controller, request);
    
相比于原始的RPC，实际调用的时候多了个 RpcController，RpcController也是一个接口，需要用户实现，用来进行RPC过程中的状态处理，例如是否出错，错误信息是什么等等。

### 服务器端需要实现什么呢？
服务器端需要实现：
1. RpcServer，实现网络数据的读取，调度分发，调用，结果写回等功能逻辑。
2. 实现 EchoService.Interface/EchoService.BlockingInterface中的RPC函数。
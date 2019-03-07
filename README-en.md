
# BaseIO Project

[![Website](https://img.shields.io/badge/website-firenio-green.svg)](https://www.firenio.com)
[![Maven central](https://img.shields.io/badge/maven-3.2.9.beta8-green.svg)](http://mvnrepository.com/artifact/com.firenio/baseio-all)
[![License](https://img.shields.io/badge/License-Apache%202.0-585ac2.svg)](https://github.com/firenio/baseio/blob/master/LICENSE.txt)

BaseIO is an io framework which can build network project fast, it based on java nio, it is popular with Developers because of simple and easy of use APIs and high-performance.

## Features

 * support protocol extend, known:
   * LengthValue protocol, for detail {baseio-test}
   * HTTP1.1 protocol(lite), for detail: https://www.firenio.com/
   * WebSocket protocol, for detail: https://www.firenio.com/web-socket/chat/index.html 
   * Protobase(custom) support text or binay, for detail {baseio-test}
 * easy to support reconnect (easy to support heart beat)
 * supported ssl (jdkssl, openssl)
 * load test
   * [tfb benchmark](https://www.techempower.com/benchmarks/#section=test&runid=ee5b26dc-6606-4925-8b30-584584cb5931&hw=ph&test=plaintext)
 
## Quick Start

 * Maven Dependency

  ```xml  
	<dependency>
		<groupId>com.firenio</groupId>
		<artifactId>baseio-all</artifactId>
		<version>3.2.9.beta8</version>
	</dependency>  
  ```
  
 * Simple Server:

  ```Java

    public static void main(String[] args) throws Exception {

        IoEventHandle eventHandleAdaptor = new IoEventHandle() {

            @Override
            public void accept(Channel ch, Frame f) throws Exception {
                String text = f.getStringContent();
                f.setContent(ch.allocate());
                f.write("yes server already accept your message:", ch);
                f.write(text, ch);
                ch.writeAndFlush(f);
            }
        };
        ChannelAcceptor context = new ChannelAcceptor(8300);
        context.addChannelEventListener(new LoggerChannelOpenListener());
        context.setIoEventHandle(eventHandleAdaptor);
        context.addProtocolCodec(new LengthValueCodec());
        context.bind();
    }

  ```

 * Simple Client:

  ```Java
    
    public static void main(String[] args) throws Exception {
        ChannelConnector context = new ChannelConnector("127.0.0.1", 8300);
        IoEventHandle eventHandle = new IoEventHandle() {
            @Override
            public void accept(Channel ch, Frame f) throws Exception {
                System.out.println();
                System.out.println("____________________" + f.getStringContent());
                System.out.println();
                context.close();
            }
        };

        context.setIoEventHandle(eventHandle);
        context.addChannelEventListener(new LoggerChannelOpenListener());
        context.addProtocolCodec(new LengthValueCodec());
        Channel ch = context.connect(3000);
        LengthValueFrame frame = new LengthValueFrame();
        frame.setString("hello server!");
        ch.writeAndFlush(frame);
    }

  ```

###	more samples see project {baseio-test}

## Sample at website:
 * HTTP Demo:https://www.firenio.com/index.html
 * WebSocket Chat Demo:https://www.firenio.com/web-socket/chat/index.html                                
  (server based on baseio,client based on: https://github.com/socketio/socket.io/ )
 * WebSocket Rumpetroll Demo:https://www.firenio.com/web-socket/rumpetroll/index.html                                
  (server based on baseio,client based on:https://github.com/danielmahal/Rumpetroll )

## License

BaseIO is released under the [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0).

## To learn more, join this QQ group, more java technique can talk at there.
 * QQ group NO: 540637859
 * Join by click this link: [![img](http://pub.idqqimg.com/wpa/images/group.png)](http://shang.qq.com/wpa/qunwpa?idkey=2bd71e10d876bb6035fa0ddc6720b5748fc8985cb666e17157d17bcfbd2bdaef)
 * Scan QR code:<br />  ![image](/baseio-doc/popularize/java-io-group-code-small.png)

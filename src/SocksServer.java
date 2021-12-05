import org.xbill.DNS.Message;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.TreeMap;

public class SocksServer {
    private int port;
    private String host = "192.168.0.1";
    private Selector selector;
    private ServerSocketChannel serv_channel;
    private DatagramChannel dns_datagram_channel;
    private ByteBuffer msg_buf;
    private TreeMap<Short, ProxyHandler> clients_handlers;
    private short client_id;
    public SocksServer(int port){
        this.port = port;
        client_id = 0;
    }

    public void start() throws IOException {
        msg_buf = ByteBuffer.allocate(1024);
        clients_handlers = new TreeMap<>();
        selector = SelectorProvider.provider().openSelector();
        serv_channel = ServerSocketChannel.open();
        serv_channel.configureBlocking(false);
        serv_channel.socket().bind(new InetSocketAddress("0.0.0.0", port));
        serv_channel.register(selector, SelectionKey.OP_ACCEPT);
        dns_datagram_channel = DatagramChannel.open();
        dns_datagram_channel.configureBlocking(false);
        dns_datagram_channel.register(selector, SelectionKey.OP_READ);
        dns_datagram_channel.connect(new InetSocketAddress(InetAddress.getByName(host), 53));
        while(selector.select()>-1){
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()){
                SelectionKey key = iterator.next();
                iterator.remove();
                if (key.isValid()) {
                    if(key == serv_channel.keyFor(selector))
                    {
                        System.out.println("Socket ready to handle client with IPv4");
                        SocketChannel client_channel = serv_channel.accept();
                        ProxyHandler new_handler = new ProxyHandler(client_id, selector, client_channel, dns_datagram_channel);
                        clients_handlers.put(client_id, new_handler);
                        client_id++;
                        continue;
                    }
                    if(key == dns_datagram_channel.keyFor(selector)){
                        System.out.println("Socket ready to handle client with DNS resolving");
                        if(dns_datagram_channel.read(msg_buf) == 0) continue;
                        msg_buf.flip();
                        short id = msg_buf.getShort();
                        System.out.println("Processing dns with id = " + id);
                        msg_buf.position(0);
                        byte[] answer_byte = new byte[msg_buf.remaining()];
                        msg_buf.get(answer_byte);
                        msg_buf.clear();
                        Message answer = new Message(answer_byte);
                        clients_handlers.get(id).dnsAccept(answer);
                        continue;
                    }
                    try {
                        if (key.isReadable()) {
                            ((ProxyHandler) (key.attachment())).read(key);
                        }
                        if (key.isWritable()) {
                            ((ProxyHandler) (key.attachment())).write(key);
                        }
                        if (key.isConnectable()) {
                            ((ProxyHandler) (key.attachment())).connect(key);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        assert (key.attachment().getClass() == ProxyHandler.class);
                        ((ProxyHandler) (key.attachment())).close(key);
                    }
                }
            }
        }
        System.out.println("Server off - select error");
    }
}


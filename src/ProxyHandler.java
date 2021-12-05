import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.*;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

public class ProxyHandler {
    private short ID;
    private SocketChannel client_channel;
    private SocketChannel destination_channel;
    private DatagramChannel dns_channel;
    private ByteBuffer client_request;
    private ByteBuffer server_response;
    private ByteBuffer chatting_buf;
    private Constants.ConnectionStep step;
    private Selector selector;

    public ProxyHandler(short ID, Selector selector, SocketChannel client_channel, DatagramChannel dns_datagram_channel) throws IOException {
        this.ID = ID;
        this.server_response = ByteBuffer.allocate(Constants.MAXBUF);
        this.client_request = ByteBuffer.allocate(Constants.MAXBUF);
        this.chatting_buf = ByteBuffer.allocate(Constants.MAXBUF);
        this.selector = selector;
        this.dns_channel = dns_datagram_channel;
        this.client_channel = client_channel;
        this.destination_channel = SocketChannel.open();
        client_channel.configureBlocking(false);
        destination_channel.configureBlocking(false);
        client_channel.register(selector, SelectionKey.OP_READ, this);
        destination_channel.register(selector, 0, this);
        step = Constants.ConnectionStep.WAITING;
    }

    public void connect(SelectionKey key) throws IOException {
        System.out.println("Connection process with target web-destination started...");
        if(!destination_channel.finishConnect()) return;
        byte[] address = ((InetSocketAddress)(destination_channel.getLocalAddress())).getAddress().getAddress();
        short port = (short)((InetSocketAddress)(destination_channel.getLocalAddress())).getPort();
        server_response.put(Constants.VERSION5)
                .put(Constants.REQUEST_PROVIDED)
                .put(Constants.RESERVE_BYTE)
                .put(Constants.IPV4_ADDRESS_TYPE)
                .put(address)
                .putShort(port)
                .flip();
        client_channel.keyFor(selector).interestOps(SelectionKey.OP_WRITE);
    }

    public void read(SelectionKey key) throws IOException {
        System.out.println("Read process started...");
        int read_msg = ((SocketChannel)key.channel()).read(chatting_buf);
        if(read_msg<1) {
            System.out.println("Disconnect or empty buffer");
            return;
        }
        if(key.channel() == client_channel){
            switch(step){
                case WAITING:{
                    System.out.println("Waiting finished - sending response with version and chosen auth method...");
                    serverChoice();
                    break;
                }
                case GREETING_ANSWER:{
                    System.out.println("Greeting answer received, client send request...");
                    chatting_buf.flip();
                    byte[] recv_bytes = chatting_buf.array();
                    if(chatting_buf.get() != Constants.VERSION5) throw new IOException("Supported version - 5, received - " + recv_bytes[0]);
                    byte command_code = chatting_buf.get();
                    if(command_code != Constants.TCP_COMMAND){
                        byte[] not_support_command = new byte[] { 0x05, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
                        server_response.put(not_support_command).flip();
                        client_channel.keyFor(selector).interestOps(SelectionKey.OP_WRITE);
                        step = Constants.ConnectionStep.CLOSE;
                        break;
                    }
                    else{
                        if(chatting_buf.get() != Constants.RESERVE_BYTE)
                            throw new IOException("Bad reserve bytes - should be 0x00, received - " + recv_bytes[2]);
                        byte address_type = chatting_buf.get();;
                        if(address_type != Constants.IPV4_ADDRESS_TYPE && address_type != Constants.DNS_ADDRESS_TYPE){
                            System.out.println("Not supported address type...");
                            byte[] not_support_address_type = new byte[] { 0x05, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
                            server_response.put(not_support_address_type).flip();
                            client_channel.keyFor(selector).interestOps(SelectionKey.OP_WRITE);
                            step = Constants.ConnectionStep.CLOSE;
                            break;
                        }
                        else if(address_type == Constants.IPV4_ADDRESS_TYPE){
                            System.out.println("Send request with IPv4...");
                            ip4Answer();
                            break;
                        }
                        else{
                            System.out.println("Send request with DNS resolve...");
                            dnsAnswer();
                            return;
                        }
                    }
                }
                case CHATTING:{
                    System.out.println("Peers started chatting - package exchanging");
                    chatting_buf.flip();
                    client_request.put(chatting_buf).flip();
                    destination_channel.keyFor(selector).interestOpsOr(SelectionKey.OP_WRITE);
                    client_channel.keyFor(selector).interestOpsAnd(~SelectionKey.OP_READ);
                    break;
                }
            }
        }
        else {
            chatting_buf.flip();
            server_response.put(chatting_buf).flip();
            client_channel.keyFor(selector).interestOpsOr(SelectionKey.OP_WRITE);
            destination_channel.keyFor(selector).interestOpsAnd(~SelectionKey.OP_READ);
        }
        chatting_buf.clear();
    }

    private void serverChoice() throws IOException {
        chatting_buf.flip();
        byte[] recv_bytes = chatting_buf.array();
        if(recv_bytes[0] != Constants.VERSION5) throw new IOException("Supported version - 5, received - " + recv_bytes[0]);
        int num_sup_methods = recv_bytes[1] & 0xFF;
        boolean no_auth = false;
        for(int i = 0; i < num_sup_methods; i++){
            byte method = recv_bytes[i+2];
            if(method == Constants.NO_AUTH_BYTE) no_auth = true;
        }
        server_response.put(Constants.VERSION5);
        if(no_auth)
        {
            server_response.put(Constants.NO_AUTH_BYTE);
        } else{
            server_response.put((byte) 0xFF);
        }
        server_response.flip();
        step = Constants.ConnectionStep.GREETING_ANSWER;
        client_channel.keyFor(selector).interestOps(SelectionKey.OP_WRITE);
    }

    private void ip4Answer() throws IOException {
        byte[] address_bytes = new byte[4];
        chatting_buf.get(address_bytes);
        InetAddress address = InetAddress.getByAddress(address_bytes);
        int port = chatting_buf.getShort();
        boolean isConnected = destination_channel.connect(new InetSocketAddress(address, port));
        if(isConnected){
            byte[] send_address = ((InetSocketAddress)(destination_channel.getLocalAddress())).getAddress().getAddress();
            short send_port = (short)((InetSocketAddress)(destination_channel.getLocalAddress())).getPort();
            server_response.put(Constants.VERSION5)
                    .put(Constants.REQUEST_PROVIDED)
                    .put(Constants.RESERVE_BYTE)
                    .put(Constants.IPV4_ADDRESS_TYPE)
                    .put(send_address)
                    .putShort(send_port)
                    .flip();
            client_channel.keyFor(selector).interestOps(SelectionKey.OP_WRITE);
            step = Constants.ConnectionStep.CHATTING;
        }
        else{
            destination_channel.keyFor(selector).interestOps(SelectionKey.OP_CONNECT);
            client_channel.keyFor(selector).interestOps(0);
            step = Constants.ConnectionStep.CONNECTING;
        }
    }

    private void dnsAnswer() throws IOException {
        Message dnsQuery = new Message(ID);
        dnsQuery.getHeader().setFlag(Flags.RD);
        System.out.println("ID: " + dnsQuery.getHeader().getID());

        chatting_buf.get();
        chatting_buf.limit(chatting_buf.limit() - 2);
        CharsetDecoder decoder = Charset.defaultCharset().newDecoder();
        CharBuffer decoded = decoder.decode(chatting_buf);
        chatting_buf.limit(chatting_buf.limit() + 2);

        Name name = new Name(decoded.toString() + ".");
        Record question = Record.newRecord(name, Type.A, DClass.IN);
        dnsQuery.addRecord(question, Section.QUESTION);

        server_response.put(dnsQuery.toWire()).flip();
        dns_channel.write(server_response);
        server_response.clear();
        client_channel.keyFor(selector).interestOps(0);
    }

    public void write(SelectionKey key) throws IOException {
        System.out.println("Writing process started...");
        if(key.channel() == client_channel){
            System.out.println("Client writing...");
            client_channel.write(server_response);
            if(server_response.remaining() != 0) return;
            if(step == Constants.ConnectionStep.GREETING_ANSWER){
                client_channel.keyFor(selector).interestOps(SelectionKey.OP_READ);
            }
            else if(step == Constants.ConnectionStep.CONNECTING){
                step = Constants.ConnectionStep.CHATTING;
                client_channel.keyFor(selector).interestOps(SelectionKey.OP_READ);
                destination_channel.keyFor(selector).interestOps(SelectionKey.OP_READ);
            }
            else if(step == Constants.ConnectionStep.CHATTING){
                client_channel.keyFor(selector).interestOpsAnd(~SelectionKey.OP_WRITE);
                destination_channel.keyFor(selector).interestOpsOr(SelectionKey.OP_READ);
            }
            else if(step == Constants.ConnectionStep.CLOSE){
                throw new IOException("Connection closed");
            }
            else{
                throw new IllegalStateException();
            }
           server_response.clear();
        }
        else {
            System.out.println("Destination web-site writing...");
            destination_channel.write(client_request);
            if(client_request.remaining() != 0) return;
            else if(step == Constants.ConnectionStep.CHATTING){
                destination_channel.keyFor(selector).interestOpsAnd(~SelectionKey.OP_WRITE);
                client_channel.keyFor(selector).interestOpsOr(SelectionKey.OP_READ);
            }
            else if(step == Constants.ConnectionStep.CLOSE){
                throw new IOException("Connection closed");
            }
            else{
                throw new IllegalStateException();
            }
            client_request.clear();
        }
        if(step == Constants.ConnectionStep.CLOSE) throw new IOException("Disconnect cause of bad bytes");
    }

    public void dnsAccept(Message answer) throws IOException {
        System.out.println("Start dns processing...");
        if(step == Constants.ConnectionStep.CONNECTING || step == Constants.ConnectionStep.CHATTING) return;
        int rcode = answer.getHeader().getRcode();
        if((rcode & Rcode.NXDOMAIN) != 0){
            System.out.println("Unknown domain name");
            byte[] bad_host_answer = new byte[] { 0x05, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
            server_response.put(bad_host_answer).flip();
            client_channel.keyFor(selector).interestOps(SelectionKey.OP_WRITE);
            step = Constants.ConnectionStep.CLOSE;
        }
        else if(rcode != 0){
            System.out.println("SOCKS server error");
            byte[] socks_serv_error = new byte[] { 0x05, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
            server_response.put(socks_serv_error).flip();
            client_channel.keyFor(selector).interestOps(SelectionKey.OP_WRITE);
            step = Constants.ConnectionStep.CLOSE;
        }
        else{
            System.out.println("Connection with domain");
            Record[] answers = answer.getSectionArray(Section.ANSWER);
            int num = 0;
            while(num < answers.length && answers[num++].getClass() != ARecord.class );
            InetAddress address = ((ARecord)(answers[num - 1])).getAddress();
            int port = chatting_buf.getShort();
            boolean isConnected = destination_channel.connect(new InetSocketAddress(address, port));
            step = Constants.ConnectionStep.CONNECTING;
            if(isConnected){
                byte[] send_address = ((InetSocketAddress)(destination_channel.getLocalAddress())).getAddress().getAddress();
                short send_port = (short)((InetSocketAddress)(destination_channel.getLocalAddress())).getPort();
                server_response.put(Constants.VERSION5)
                        .put(Constants.REQUEST_PROVIDED)
                        .put(Constants.RESERVE_BYTE)
                        .put(Constants.IPV4_ADDRESS_TYPE)
                        .put(send_address)
                        .putShort(send_port)
                        .flip();
                step = Constants.ConnectionStep.CHATTING;
                client_channel.keyFor(selector).interestOps(SelectionKey.OP_WRITE);
            }
            else{
                destination_channel.keyFor(selector).interestOps(SelectionKey.OP_CONNECT);
                client_channel.keyFor(selector).interestOps(0);
                step = Constants.ConnectionStep.CONNECTING;
            }
        }
        chatting_buf.clear();
    }

    public void close(SelectionKey key) {
        System.out.println("Closing channels");
        try {
            key.cancel();
            if (destination_channel.isOpen())
                destination_channel.close();
            if (client_channel.isOpen())
                client_channel.close();
        } catch(IOException e){
            System.exit(-1);
        }
    }
}

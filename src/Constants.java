public interface Constants {
    int MAXBUF = 8192;
    byte VERSION5 = 0x05;
    byte REQUEST_PROVIDED = 0x00;
    byte TCP_COMMAND = 0x01;
    byte RESERVE_BYTE = 0x00;
    byte NO_AUTH_BYTE = 0x00;
    byte IPV4_ADDRESS_TYPE = 0x01;
    byte DNS_ADDRESS_TYPE = 0x03;
    enum ConnectionStep{
        WAITING,
        GREETING_ANSWER,
        CONNECTING,
        CHATTING,
        CLOSE
    }
}

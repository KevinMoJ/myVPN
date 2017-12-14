package com.vm.shadowsocks.core;

import com.vm.shadowsocks.tcpip.CommonMethods;
import com.vm.shadowsocks.tunnel.Tunnel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

public class TcpProxyServer implements Runnable {

    public boolean Stopped;
    public short Port;

    Selector m_Selector;
    ServerSocketChannel m_ServerSocketChannel;
    Thread m_ServerThread;

    public TcpProxyServer(int port) throws IOException {
        m_Selector = Selector.open();
        m_ServerSocketChannel = ServerSocketChannel.open();
        m_ServerSocketChannel.configureBlocking(false);
        m_ServerSocketChannel.socket().bind(new InetSocketAddress(port));
        m_ServerSocketChannel.register(m_Selector, SelectionKey.OP_ACCEPT);
        this.Port = (short) m_ServerSocketChannel.socket().getLocalPort();
        System.out.printf("AsyncTcpServer listen on %d success.\n", this.Port & 0xFFFF);
    }

    public void start() {
        start("TcpProxyServerThread");
    }

    public void stop() {
        this.Stopped = true;
        if (m_Selector != null) {
            try {
                m_Selector.close();
                m_Selector = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (m_ServerSocketChannel != null) {
            try {
                m_ServerSocketChannel.close();
                m_ServerSocketChannel = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                m_Selector.select();
                Iterator<SelectionKey> keyIterator = m_Selector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    if (key.isValid()) {
                        try {
                            if (ProxyConfig.IS_DEBUG) {
                                if (key.isReadable()) {
                                    System.out.println("isReadable");
                                } else if (key.isWritable()) {
                                    System.out.println("isWritable");
                                } else if (key.isConnectable()) {
                                    System.out.println("isConnectable");
                                } else if (key.isAcceptable()) {
                                    System.out.println("isAcceptable");
                                }
                                Tunnel tunnel = ((Tunnel) key.attachment());
                                if (tunnel != null) {
                                    tunnel.toString();
                                }
                            }
                            if (key.isReadable()) {
                                ((Tunnel) key.attachment()).onReadable(key);
                            } else if (key.isWritable()) {
                                ((Tunnel) key.attachment()).onWritable(key);
                            } else if (key.isConnectable()) {
                                ((Tunnel) key.attachment()).onConnectable();
                            } else if (key.isAcceptable()) {
                                onAccepted(key);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            String operation = "null";
                            if (key.isReadable()) {
                                System.out.println("read");
                            } else if (key.isWritable()) {
                                System.out.println("write");
                            } else if (key.isConnectable()) {
                                System.out.println("connect");
                            } else if (key.isAcceptable()) {
                                System.out.println("accept");
                            }
                            LocalVpnService.Instance.writeLog("Error: socket %s failed: %s", operation, e);
                        }
                    }
                    keyIterator.remove();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            this.stop();
            System.out.println("TcpServer thread exited.");
        }
    }

    InetSocketAddress getDestAddress(SocketChannel localChannel) {
        short portKey = (short) localChannel.socket().getPort();
        NatSession session = NatSessionManager.getSession(portKey);
        if (session != null) {
            if (needProxy(session)) {
                if (ProxyConfig.IS_DEBUG)
                    System.out.printf("%d/%d:[PROXY] %s=>%s:%d\n", NatSessionManager.getSessionCount(), Tunnel.SessionCount, session.RemoteHost, CommonMethods.ipIntToString(session.RemoteIP), session.RemotePort & 0xFFFF);
                return InetSocketAddress.createUnresolved(session.RemoteHost, session.RemotePort & 0xFFFF);
            } else {
                return new InetSocketAddress(localChannel.socket().getInetAddress(), session.RemotePort & 0xFFFF);
            }
        }
        return null;
    }

    void onAccepted(SelectionKey key) {
        Tunnel localTunnel = null;
        Tunnel remoteTunnel = null;

        try {
            SocketChannel localChannel = m_ServerSocketChannel.accept();
            localTunnel = TunnelFactory.wrap(localChannel, m_Selector);
            InetSocketAddress destAddress = getDestAddress(localChannel);
            if (ProxyConfig.IS_DEBUG){
                Socket localSocket = localChannel.socket();
                System.out.printf("TcpProxyServer onAccept localSocket %s\n", localSocket);
                System.out.printf("TcpProxyServer onAccept destAddress %s\n", destAddress);

            }
            if (destAddress != null) {
                remoteTunnel = TunnelFactory.createTunnelByConfig(destAddress, m_Selector);

                remoteTunnel.setBrotherTunnel(localTunnel);//关联兄弟
                localTunnel.setBrotherTunnel(remoteTunnel);//关联兄弟
                remoteTunnel.connect(destAddress);//开始连接
            } else {
                LocalVpnService.Instance.writeLog("Error: socket(%s:%d) target host is null.", localChannel.socket().getInetAddress().toString(), localChannel.socket().getPort());
                localTunnel.dispose();
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (remoteTunnel == null) {
                LocalVpnService.Instance.writeLog("Error: remote socket create failed: %s", e);
            } else {
                LocalVpnService.Instance.writeLog("Error: remote socket connect failed: %s", e);
            }
            if (localTunnel != null) {
                localTunnel.dispose();
            }
        }
    }

    public Thread getThread(){
        return m_ServerThread;
    }

    protected void start(String threadname) {
        m_ServerThread = new Thread(this);
        m_ServerThread.setName(threadname);
        m_ServerThread.start();
    }

    protected boolean needProxy(NatSession session) {
        return ProxyConfig.Instance.needProxy(session.RemoteHost, session.RemoteIP);
    }

}
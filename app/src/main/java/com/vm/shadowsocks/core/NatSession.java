package com.vm.shadowsocks.core;

public class NatSession {
    public int RemoteIP;
    public short RemotePort;
    public String RemoteHost;
    public int BytesSent;
    public int PacketSent;
    public long LastNanoTime;
    public int IsSelfPort;
    public int RemoteRealIP;
}

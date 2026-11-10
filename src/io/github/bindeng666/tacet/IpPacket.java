package io.github.bindeng666.tacet;

import java.io.IOException;
import java.util.Arrays;

/** Only the two routed synthetic DNS addresses can reach this codec. Supports IPv4/IPv6 and UDP/TCP. */
public final class IpPacket {
    public final byte[] wire, source, destination, data;
    public final int version, protocol, sourcePort, destinationPort, tcpFlags;
    public final long sequence, acknowledgement;
    private IpPacket(byte[] wire,int version,int protocol,byte[] source,byte[] destination,int sp,int dp,int flags,long seq,long ack,byte[] data) {
        this.wire=wire;this.version=version;this.protocol=protocol;this.source=source;this.destination=destination;
        sourcePort=sp;destinationPort=dp;tcpFlags=flags;sequence=seq;acknowledgement=ack;this.data=data;
    }
    static int u16(byte[] b,int i){return DnsMessage.u16(b,i);}
    static long u32(byte[] b,int i){return ((long)u16(b,i)<<16)|u16(b,i+2);}
    static void p16(byte[] b,int i,int v){DnsMessage.put16(b,i,v);}
    static void p32(byte[] b,int i,long v){p16(b,i,(int)(v>>>16));p16(b,i+2,(int)v);}
    public static IpPacket parse(byte[] b,int length) throws IOException {
        if(length<20||length>b.length)throw new IOException("Short IP packet");
        int version=(b[0]>>4)&15,off,proto,end; byte[] src,dst;
        if(version==4){
            off=(b[0]&15)*4;end=u16(b,2);proto=b[9]&255;
            if(off<20||end>length||end<off||(u16(b,6)&0x3fff)!=0)throw new IOException("Invalid or fragmented IPv4");
            src=Arrays.copyOfRange(b,12,16);dst=Arrays.copyOfRange(b,16,20);
        }else if(version==6){
            if(length<40)throw new IOException("Short IPv6");off=40;end=40+u16(b,4);proto=b[6]&255;
            if(end>length)throw new IOException("Invalid IPv6 length");
            src=Arrays.copyOfRange(b,8,24);dst=Arrays.copyOfRange(b,24,40);
        }else throw new IOException("Unknown IP version");
        if(end<off+8)throw new IOException("Short transport header");
        int sp=u16(b,off),dp=u16(b,off+2),flags=0;long seq=0,ack=0;
        if(proto==17){int len=u16(b,off+4);if(len<8||off+len>end)throw new IOException("UDP length");end=off+len;off+=8;}
        else if(proto==6){
            if(end<off+20)throw new IOException("TCP header");int n=((b[off+12]>>4)&15)*4;
            if(n<20||off+n>end)throw new IOException("TCP offset");seq=u32(b,off+4);ack=u32(b,off+8);flags=b[off+13]&255;off+=n;
        }else throw new IOException("Not DNS transport");
        if(dp!=53)throw new IOException("Not DNS");
        return new IpPacket(Arrays.copyOf(b,end),version,proto,src,dst,sp,dp,flags,seq,ack,Arrays.copyOfRange(b,off,end));
    }
    public byte[] udpReply(byte[] payload){return reply(payload,17,0,0,0);}
    public byte[] tcpReply(byte[] payload,int flags,long sequence,long acknowledgement){return reply(payload,6,flags,sequence,acknowledgement);}
    private byte[] reply(byte[] payload,int proto,int flags,long seq,long ack){
        int ip=version==4?20:40,head=proto==17?8:20;byte[] b=new byte[ip+head+payload.length];
        if(version==4){b[0]=0x45;p16(b,2,b.length);b[6]=0x40;b[8]=64;b[9]=(byte)proto;
            System.arraycopy(destination,0,b,12,4);System.arraycopy(source,0,b,16,4);p16(b,10,checksum(b,0,20,0));
        }else{b[0]=0x60;p16(b,4,b.length-40);b[6]=(byte)proto;b[7]=64;System.arraycopy(destination,0,b,8,16);System.arraycopy(source,0,b,24,16);}
        p16(b,ip,destinationPort);p16(b,ip+2,sourcePort);
        if(proto==17)p16(b,ip+4,head+payload.length);
        else {p32(b,ip+4,seq);p32(b,ip+8,ack);b[ip+12]=0x50;b[ip+13]=(byte)flags;p16(b,ip+14,65535);}
        System.arraycopy(payload,0,b,ip+head,payload.length);
        long sum=proto+head+payload.length;
        for(int i=0;i<source.length;i+=2)sum+=u16(source,i)+u16(destination,i);
        int c=checksum(b,ip,head+payload.length,sum);p16(b,ip+(proto==17?6:16),c==0?65535:c);
        return b;
    }
    public static int checksum(byte[] b,int off,int length,long initial){
        long sum=initial;int end=off+length;
        for(int i=off;i<end;i+=2)sum+=((b[i]&255)<<8)+(i+1<end?(b[i+1]&255):0);
        while((sum>>>16)!=0)sum=(sum&65535)+(sum>>>16);return (int)(~sum)&65535;
    }
    public String flowKey(){return Arrays.toString(source)+":"+sourcePort+":"+version;}
}

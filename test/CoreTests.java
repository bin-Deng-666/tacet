package io.github.bindeng666.tacet.test;

import io.github.bindeng666.tacet.*;
import java.io.IOException;
import java.util.*;

/** Protocol edge cases that could otherwise break app networking. No Android runtime required. */
public final class CoreTests {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void invalid(byte[] b){try{DnsMessage.parse(b);throw new AssertionError("Malformed DNS accepted");}catch(IOException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        DomainPolicy rules=new DomainPolicy(Arrays.asList("ADS.Example.com.","*.doubleclick.net"),Arrays.asList("safe.ads.example.com"));
        check(rules.blocks("ads.example.com"),"Exact block");check(rules.blocks("x.ads.example.com"),"Subdomain block");
        check(!rules.blocks("notads.example.com"),"Label boundary");check(!rules.blocks("ads.example.com.evil.org"),"Suffix confusion");
        check(!rules.blocks("safe.ads.example.com"),"Allow overrides block");check(!rules.blocks("x.safe.ads.example.com"),"Allow covers subdomain");
        check(!rules.blocks("example.com"),"Parent not blocked");check(rules.blocks("a.doubleclick.net"),"Wildcard normalized");
        check(DomainPolicy.normalize("https://ads.example.com/x").isEmpty(),"Reject URLs");check(DomainPolicy.normalize("example..com").isEmpty(),"Reject empty labels");
        check(DomainPolicy.normalize("COM").isEmpty(),"Reject blanket top-level domain");
        byte[] q=DnsMessage.query("www.example.com",0x1234,28);DnsMessage dns=DnsMessage.parse(q);
        check(dns.name.equals("www.example.com")&&dns.type==28,"Parse AAAA query");
        byte[] blocked=dns.error(3);check((blocked[3]&15)==3&&(blocked[2]&128)!=0,"NXDOMAIN response");
        check(DnsMessage.u16(blocked,4)==1&&DnsMessage.u16(blocked,6)==0,"Question preserved without answer");
        check(dns.accepts(blocked),"Accept matching response");blocked[0]++;check(!dns.accepts(blocked),"Reject wrong transaction");
        invalid(new byte[0]);invalid(Arrays.copyOf(q,14));byte[] evil=q.clone();evil[12]=(byte)0xc0;invalid(evil);
        evil=q.clone();evil[4]=127;invalid(evil);evil=q.clone();evil[2]=(byte)128;invalid(evil);
        for(int v:new int[]{4,6}){
            int ip=v==4?20:40;byte[] packet=new byte[ip+8+q.length];
            if(v==4){packet[0]=0x45;DnsMessage.put16(packet,2,packet.length);packet[9]=17;packet[12]=10;packet[15]=1;packet[16]=10;packet[19]=2;}
            else{packet[0]=0x60;DnsMessage.put16(packet,4,packet.length-40);packet[6]=17;packet[8]=(byte)0xfd;packet[23]=1;packet[24]=(byte)0xfd;packet[39]=2;}
            DnsMessage.put16(packet,ip,50123);DnsMessage.put16(packet,ip+2,53);DnsMessage.put16(packet,ip+4,8+q.length);System.arraycopy(q,0,packet,ip+8,q.length);
            IpPacket parsed=IpPacket.parse(packet,packet.length);check(parsed.sourcePort==50123&&parsed.data.length==q.length,"Parse UDP IPv"+v);
            byte[] response=parsed.udpReply(dns.error(3));check(DnsMessage.u16(response,ip)==53&&DnsMessage.u16(response,ip+2)==50123,"Swap UDP ports");
            check(response[(v==4?12:8)+(v==4?3:15)]==2,"Swap source IP");
            if(v==4)check(IpPacket.checksum(response,0,20,0)==0,"IPv4 header checksum");
            long pseudo=17+response.length-ip;int srcOff=v==4?12:8,dstOff=v==4?16:24,len=v==4?4:16;
            for(int i=0;i<len;i+=2)pseudo+=DnsMessage.u16(response,srcOff+i)+DnsMessage.u16(response,dstOff+i);
            check(IpPacket.checksum(response,ip,response.length-ip,pseudo)==0,"UDP checksum IPv"+v);
            byte[] tcp=parsed.tcpReply(new byte[]{0,1,2},0x19,0xfedcba98L,0x12345678L);
            check((tcp[ip+13]&255)==0x19,"TCP flags");check(DnsMessage.u16(tcp,ip+4)==0xfedc,"TCP sequence upper word");
            try{IpPacket.parse(Arrays.copyOf(packet,ip+4),ip+4);throw new AssertionError("Short packet accepted");}catch(IOException expected){checks++;}
            if(v==4){packet[6]=0x20;try{IpPacket.parse(packet,packet.length);throw new AssertionError("Fragment accepted");}catch(IOException expected){checks++;}}
        }
        System.out.println("PASS: "+checks+" core protocol and domain-policy checks");
    }
}

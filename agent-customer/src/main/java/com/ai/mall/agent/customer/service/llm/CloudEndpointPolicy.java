package com.ai.mall.agent.customer.service.llm;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** User-supplied cloud endpoints must use public HTTPS, without credentials in the URL. */
public class CloudEndpointPolicy {
    private final Function<String,List<InetAddress>> resolver;
    public CloudEndpointPolicy() {
        this(host -> { try { return List.of(InetAddress.getAllByName(host)); }
            catch (Exception ignored) { throw bad("无法解析云端接口地址"); } });
    }
    CloudEndpointPolicy(Function<String,List<InetAddress>> resolver) { this.resolver=resolver; }
    public String validate(String supplied) {
        try {
            if (supplied == null || supplied.length() > 512) throw bad("请填写公网 HTTPS 接口地址");
            URI uri = URI.create(supplied.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw bad("云端接口须使用公网 HTTPS，不能在地址中放密钥");
            List<InetAddress> addresses=resolver.apply(uri.getHost());
            if (addresses.isEmpty() || addresses.stream().anyMatch(CloudEndpointPolicy::privateAddress))
                throw bad("云端接口不能使用本机、内网或保留地址");
            String base=uri.toString().replaceAll("/+$", "");
            return base.endsWith("/chat/completions") ? base
                    : base + (uri.getPath() == null || uri.getPath().isBlank() || "/".equals(uri.getPath())
                    ? "/v1/chat/completions" : "/chat/completions");
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception ignored) { throw bad("云端接口地址格式不正确"); }
    }
    private static boolean privateAddress(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b=a.getAddress();
        if (b.length == 16) return (b[0]&0xe0)!=0x20 || (b[0]&255)==0x20 && (b[1]&255)==1 && (b[2]&255)==13 && (b[3]&255)==184;
        int first=b[0]&255, second=b[1]&255;
        return first==0 || first>=224 || first==100 && second>=64 && second<=127
                || first==192 && (second==0 || second==2) || first==198 && (second==18 || second==19 || second==51)
                || first==203 && second==0;
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}

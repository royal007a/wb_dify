package com.hify.mcp.application;
import com.hify.common.*; import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Component; import java.net.*; import java.util.*;
@Component
public class McpEndpointGuard {
 private final boolean allowPrivate;
 public McpEndpointGuard(@Value("${hify.mcp.allow-private:false}") boolean allowPrivate){this.allowPrivate=allowPrivate;}
 public URI validate(String value){
  try{
   URI uri=URI.create(value); if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null) throw invalid();
   if(!allowPrivate) for(InetAddress address:InetAddress.getAllByName(uri.getHost())) if(blocked(address)) throw new BizException(ErrorCode.FORBIDDEN,"MCP endpoint resolves to a private or local address");
   return uri;
  }catch(BizException e){throw e;}catch(Exception e){throw new BizException(ErrorCode.PARAM_ERROR,"Invalid MCP endpoint URL");}
 }
 private boolean blocked(InetAddress a){if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress())return true;byte[] b=a.getAddress();return b.length==4&&(b[0]&255)==169&&(b[1]&255)==254;}
 private BizException invalid(){return new BizException(ErrorCode.PARAM_ERROR,"MCP endpoint must be an http(s) URL without userinfo or fragment");}
}

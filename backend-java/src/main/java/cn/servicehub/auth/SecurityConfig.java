package cn.servicehub.auth;

import cn.servicehub.common.BusinessException;
import cn.servicehub.config.ServiceHubProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean SecurityFilterChain servicehubSecurity(HttpSecurity http,AuthService auth,ServiceHubProperties properties,
        @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) throws Exception {
        if("production".equals(properties.environment())&&!properties.cookieSecure())throw new IllegalArgumentException("Production requires secure cookies and HTTPS");
        var cors=new CorsConfiguration();cors.setAllowedOrigins(properties.allowedOrigins());cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET","POST","PATCH","PUT","DELETE","OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type","x-csrf-token","x-request-id"));cors.setExposedHeaders(List.of("x-request-id","Retry-After"));
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",cors);
        var liveCors=new CorsConfiguration();liveCors.setAllowedOriginPatterns(List.of("*"));liveCors.setAllowedMethods(List.of("GET"));
        http.csrf(AbstractHttpConfigurer::disable).cors(c->c.configurationSource(request->isLiveHandshake(request)?liveCors:source.getCorsConfiguration(request)))
            .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(c->c.requestMatchers(SecurityConfig::isLiveHandshake).permitAll().requestMatchers("/api/v1/health/**","/api/v1/auth/login").permitAll().requestMatchers("/actuator/**").hasRole("ADMIN").anyRequest().authenticated())
            .exceptionHandling(c->c.authenticationEntryPoint((req,res,e)->errors.resolveException(req,res,null,SessionStore.unauthorized("请先登录")))
                .accessDeniedHandler((req,res,e)->errors.resolveException(req,res,null,new BusinessException("forbidden","当前账号无权执行此操作",403))));
        http.addFilterAfter(new OncePerRequestFilter(){
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
                String path=request.getRequestURI();boolean safe=List.of("GET","HEAD","OPTIONS").contains(request.getMethod());
                if(isLiveHandshake(request)||path.startsWith("/api/v1/health/")||"OPTIONS".equals(request.getMethod())){chain.doFilter(request,response);return;}
                try {
                    if(!safe)checkOrigin(request,properties);
                    if(!path.equals("/api/v1/auth/login")){
                        String token=request.getCookies()==null?null:Arrays.stream(request.getCookies()).filter(c->c.getName().equals(properties.sessionCookieName())).findFirst().map(Cookie::getValue).orElse(null);
                        var session=auth.authenticateSession(token);
                        if(!safe&&!SessionStore.safeEquals(request.getHeader("x-csrf-token"),session.csrfToken()))throw new BusinessException("csrf_rejected","请求校验失败，请刷新后重试",403);
                        request.setAttribute(AuthController.AUTH_ATTRIBUTE,session);
                        var principal=new UsernamePasswordAuthenticationToken(session.actor(),null,List.of(new SimpleGrantedAuthority("ROLE_"+session.actor().role().name())));
                        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(principal);SecurityContextHolder.setContext(context);
                    }
                    chain.doFilter(request,response);
                }catch(BusinessException e){errors.resolveException(request,response,null,e);}
                catch(org.springframework.dao.DataAccessException e){errors.resolveException(request,response,null,SessionStore.dependencyFailure());}
            }
        },SecurityContextHolderFilter.class);
        return http.build();
    }
    private static boolean isLiveHandshake(HttpServletRequest request){
        return "GET".equals(request.getMethod())&&"websocket".equalsIgnoreCase(request.getHeader("Upgrade"))&&request.getRequestURI().matches("/api/v1/conversations/[1-9][0-9]*/live");
    }
    private static void checkOrigin(HttpServletRequest request,ServiceHubProperties properties){
        String origin=request.getHeader("Origin");if(origin!=null&&!properties.allowedOrigins().contains(origin))throw new BusinessException("origin_denied","请求来源不被允许",403);
    }
}

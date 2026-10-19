package io.frayground.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

@Configuration
public class SpaWebConfiguration implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .resourceChain(true)
        .addResolver(
            new PathResourceResolver() {
              @Override
              protected Resource getResource(String resourcePath, Resource location)
                  throws IOException {
                if (resourcePath.startsWith("api/")) {
                  return null;
                }
                Resource requested = location.createRelative(resourcePath);
                if (requested.exists() && requested.isReadable()) {
                  return requested;
                }
                Resource index = location.createRelative("index.html");
                if (index.exists() && index.isReadable()) {
                  return index;
                }
                return null;
              }
            });
  }
}

package com.parking.config;

import com.google.code.kaptcha.impl.DefaultKaptcha;
import com.google.code.kaptcha.util.Config;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

@Configuration
@EnableConfigurationProperties(CaptchaProperties.class)
public class KaptchaConfig {

    @Bean
    public DefaultKaptcha kaptchaProducer(CaptchaProperties captchaProperties) {
        Properties properties = new Properties();
        properties.put("kaptcha.image.width", String.valueOf(captchaProperties.getWidth()));
        properties.put("kaptcha.image.height", String.valueOf(captchaProperties.getHeight()));
        properties.put("kaptcha.textproducer.char.length", String.valueOf(captchaProperties.getLength()));
        properties.put("kaptcha.textproducer.char.space", "4");
        properties.put("kaptcha.border", "no");
        properties.put("kaptcha.textproducer.font.color", "26,26,26");
        properties.put("kaptcha.textproducer.font.size", "34");
        properties.put("kaptcha.noise.color", "120,120,120");
        properties.put("kaptcha.background.clear.from", "238,242,247");
        properties.put("kaptcha.background.clear.to", "255,255,255");

        DefaultKaptcha producer = new DefaultKaptcha();
        producer.setConfig(new Config(properties));
        return producer;
    }
}

package com.tokensea;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.tokensea.**.mapper")
public class TokenseaApplication {
    public static void main(String[] args) {
        SpringApplication.run(TokenseaApplication.class, args);
    }
}

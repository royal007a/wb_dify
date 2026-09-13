package com.hify;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan(basePackages = "com.hify", annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class HifyApplication {
    public static void main(String[] args) {
        SpringApplication.run(HifyApplication.class, args);
    }
}

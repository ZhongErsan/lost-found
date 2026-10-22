package com.lostsystem.tools;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class TestTools {
    @Tool("获取用户所在班级")
    public String getUserClass(String userName){
        System.out.println("---"+userName);
        return "四班";
    }
    @Tool("获取今天天气")
    public String getWeather(String address){
        System.out.println("---"+address);
        return "天气晴朗";
    }

}


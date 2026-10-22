package com.lostsystem.aioutput;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

@Data
public class IntentionOutput {
    @Description("意图分析 1：代表的是丢失信息登记；2：找到失物登记；3：失物查询；4：其他")
    private Integer intention;
    @Description("大模型对用户的输出")
    private String output;
}

package com.lostsystem.aioutput;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

@Data
public class LostRegisterOutput {

    @Description("回复给用户的自然语言内容")
    private String output;

    @Description("用户姓名，如果用户没有提供则为null，不要自行编造")
    private String userName;

    @Description("用户手机号，如果用户没有提供则为null，不要自行编造")
    private String phone;

    @Description("丢失物品名称，例如手机、钱包、AirPods")
    private String lostName;

    @Description("丢失物品的外观和特征，例如颜色、品牌、型号、划痕、挂件等")
    private String lostFeature;

    @Description("丢失地点，如果用户没有提供则为null")
    private String lostLocation;

    @Description("用户描述的丢失时间，例如昨天下午三点")
    private String lostTime;

    @Description("登记确认")
    private Boolean completed;

    @Description("""
        修改目标的数据库真实记录ID。
        CREATE 时必须为 null；
        UPDATE 时必须通过查询本人登记工具获取，不得编造。
        """)
    private Long id;

    @Description("用户是否已经确认使用默认联系人信息")
    private Boolean contactConfirmed;
    @Description("""
        本轮登记操作类型：
        CREATE 表示新增登记；
        UPDATE 表示补充或修改已有登记。
        补充已有登记即使尚未查到记录ID，也必须保持 UPDATE。
        """)
    private RegistrationAction action;
}
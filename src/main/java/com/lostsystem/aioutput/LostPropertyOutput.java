package com.lostsystem.aioutput;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

@Data
public class LostPropertyOutput {
    @Description("大模型对用户的输出")
    private String output;
    @Description("拾得人姓名；来自系统账号资料或原登记记录，不得编造")
    private String username;
    @Description("固定返回null；完整手机号由后端读取或保留，脱敏号码在output中展示")
    private String phone;
    @Description("拾到物品的简短名称，例如钱包、钥匙、耳机")
    private String lostName;
    @Description("拾到物品的特征；修改时保留未要求修改的信息")
    private String lostType;
    @Description("""
    信息完整且用户明确确认本轮内容后为true，表示提交后端执行保存；
    信息不足、目标不明或尚未确认时为false。
    """)
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

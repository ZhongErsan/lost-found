package com.lostsystem.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import com.lostsystem.service.LostPropertyIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class QueryLostPropertyTools {
    private final LostPropertyIndexService indexService;

    @Tool("根据物品名称和特征查询所有用户的拾物线索，结果仅为候选，不代表已确认物品归属")
    public List<String> queryLostProperty(@P("物品名称和特征") String description) {
        return indexService.search(description);
    }
}

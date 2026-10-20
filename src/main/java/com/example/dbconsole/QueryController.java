package com.example.dbconsole;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class QueryController {

    private static final String DEFAULT_SQL = "SELECT NOW() AS server_time, DATABASE() AS current_db, USER() AS connected_as";

    private final QueryService queryService;
    private final String podName;

    // HOSTNAME is set by Kubernetes to the pod name, so the page shows which replica answered.
    public QueryController(QueryService queryService, @Value("${HOSTNAME:local}") String podName) {
        this.queryService = queryService;
        this.podName = podName;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("sql", DEFAULT_SQL);
        model.addAttribute("podName", podName);
        return "index";
    }

    @PostMapping("/query")
    public String query(@RequestParam("sql") String sql, Model model) {
        model.addAttribute("sql", sql);
        model.addAttribute("result", queryService.execute(sql));
        model.addAttribute("podName", podName);
        return "index";
    }

    @GetMapping("/connection")
    public String connection(Model model) {
        model.addAttribute("sql", DEFAULT_SQL);
        model.addAttribute("connectionInfo", queryService.connectionInfo());
        model.addAttribute("podName", podName);
        return "index";
    }
}

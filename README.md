# Zabbix Java SDK

基于Java 21开发的Zabbix API官方风格SDK，参考Python的`zabbix_utils`工具库设计。

## 特性

- ✅ **原生API风格**: 完全符合Zabbix官方API设计规范
- ✅ **类型安全**: 使用Java泛型确保类型安全
- ✅ **链式调用**: 流畅的API调用体验
- ✅ **自动认证**: 自动管理会话token，**自动检测过期并重新登录**
- ✅ **异常处理**: 详细的异常信息和错误码
- ✅ **模块化设计**: 按功能划分API模块
- ✅ **线程安全**: 并发场景下的自动登录是线程安全的
- ✅ **零依赖**: 仅依赖Java 11+ HttpClient和Jackson

## 🆕 自动登录机制

SDK内置智能的自动登录机制，**基于实际API响应检测token失效**，无需依赖固定超时时间：

### 核心特性

1. **实时检测**: 通过检测API响应中的会话错误（而非固定超时时间）来判断token是否失效
2. **自动刷新**: 检测到token失效时自动重新获取token
3. **透明重试**: 会话错误时自动刷新token并重试调用，对业务代码透明
4. **线程安全**: 并发场景下确保只有一个线程执行token刷新
5. **灵活认证**: 支持用户名密码、直接token、自定义token提供者等多种方式

### 三种认证方式

#### 方式1: 用户名+密码（推荐）

```java
ZabbixAPI api = new ZabbixAPI("http://zabbix.example.com/api_jsonrpc.php");
api.login("Admin", "zabbix");

// SDK会自动管理token，完全不用担心过期
for (int i = 0; i < 1000; i++) {
    api.host().get();  // 自动处理token过期
}
```

#### 方式2: 直接使用已有token

```java
String existingToken = "your_token_here";

ZabbixAPI api = new ZabbixAPI("http://zabbix.example.com/api_jsonrpc.php");
api.withToken(existingToken);

// 正常使用，但token失效后会抛出异常（没有自动刷新）
api.host().get();
```

#### 方式3: 使用token + 自定义刷新逻辑

```java
String initialToken = getTokenFromConfigCenter();

ZabbixAPI api = new ZabbixAPI("http://zabbix.example.com/api_jsonrpc.php");
api.withToken(initialToken, () -> {
    // 自定义token刷新逻辑
    return refreshTokenFromConfigCenter();
});

// SDK会在token失效时自动调用刷新逻辑
api.host().get();
```

### 错误检测机制

SDK通过检测以下特征判断token是否失效：

- **错误码**: -32602 (Invalid params), -32500 (Application error), -32600 (Invalid request)
- **错误消息**: 包含 "session", "not authorized", "not authenticated", "session terminated" 等关键词

```java
// 自动检测这些Zabbix响应
{
  "error": {
    "code": -32602,
    "message": "Session terminated, re-login, please.",
    "data": "..."
  }
}
// SDK会自动刷新token并重试
```

## 快速开始

### 基本使用

```java
// 1. 创建API实例
ZabbixAPI api = new ZabbixAPI("http://zabbix.example.com/api_jsonrpc.php");

// 2. 登录
api.login("Admin", "zabbix");

// 3. 调用API
List<Map<String, Object>> hosts = api.host().get(
    ZabbixParams.builder()
        .output(Arrays.asList("hostid", "host"))
        .limit(10)
        .build()
);

// 4. 登出
api.logout();
```

### 事件确认（核心功能）

```java
// 方法1: 完整参数
Map<String, Object> result = api.event().acknowledge(
    Arrays.asList("12345", "12346"),
    ZabbixParams.builder()
        .param("action", 1)  // 1=关闭, 2=确认, 4=添加消息
        .param("message", "问题已解决")
        .build()
);

// 方法2: 简化调用
api.event().acknowledge(
    Arrays.asList("12345", "12346"),
    1,  // action
    "问题已解决"  // message
);

// 方法3: 快捷关闭
api.event().close(
    Arrays.asList("12345", "12346"),
    "批量关闭事件"
);
```

## API模块

### Host API - 主机管理

```java
// 获取所有主机
List<Map<String, Object>> hosts = api.host().get();

// 按名称获取主机
List<Map<String, Object>> hosts = api.host().getByName("Zabbix server");

// 高级查询
List<Map<String, Object>> hosts = api.host().get(
    ZabbixParams.builder()
        .output(Arrays.asList("hostid", "host", "status"))
        .filter("status", 0)  // 0=启用
        .search("host", "server")
        .sortfield("host")
        .limit(100)
        .build()
);

// 创建主机
Map<String, Object> newHost = new HashMap<>();
newHost.put("host", "New Server");
newHost.put("groups", Arrays.asList(Map.of("groupid", "2")));
api.host().create(newHost);

// 更新主机
Map<String, Object> update = new HashMap<>();
update.put("hostid", "10084");
update.put("status", 1);  // 禁用
api.host().update(update);

// 删除主机
api.host().delete(Arrays.asList("10084", "10085"));
```

### Event API - 事件管理

```java
// 获取事件
List<Map<String, Object>> events = api.event().get(
    ZabbixParams.builder()
        .param("eventids", Arrays.asList("12345"))
        .outputExtend()
        .build()
);

// 确认事件（多种action组合）
api.event().acknowledge(
    Arrays.asList("12345"),
    ZabbixParams.builder()
        .param("action", 1 | 4)  // 1=关闭, 4=添加消息
        .param("message", "问题已修复")
        .param("severity", 0)  // 改变严重程度
        .build()
);

// 关闭事件
api.event().close(Arrays.asList("12345", "12346"), "批量关闭");
```

### Item API - 监控项管理

```java
// 按主机获取监控项
List<Map<String, Object>> items = api.item().getByHost("10084");

// 按key搜索监控项
List<Map<String, Object>> items = api.item().getByKey("system.cpu.load");

// 创建监控项
Map<String, Object> newItem = new HashMap<>();
newItem.put("name", "CPU Load");
newItem.put("key_", "system.cpu.load[percpu,avg1]");
newItem.put("hostid", "10084");
newItem.put("type", 0);  // Zabbix agent
newItem.put("value_type", 0);  // float
newItem.put("delay", "60s");
api.item().create(newItem);
```

### Trigger API - 触发器管理

```java
// 获取活动触发器
List<Map<String, Object>> triggers = api.trigger().getActive();

// 按主机获取触发器
List<Map<String, Object>> triggers = api.trigger().getByHost("10084");

// 高级查询
List<Map<String, Object>> triggers = api.trigger().get(
    ZabbixParams.builder()
        .filter("priority", 5)  // 5=灾难级
        .param("selectHosts", "extend")
        .outputExtend()
        .build()
);
```

### Problem API - 问题管理

```java
// 获取未解决的问题
List<Map<String, Object>> problems = api.problem().getUnresolved();

// 按严重程度获取问题
List<Map<String, Object>> problems = api.problem().getBySeverity(5);

// 高级过滤
List<Map<String, Object>> problems = api.problem().get(
    ZabbixParams.builder()
        .param("recent", true)
        .param("suppressed", false)
        .filter("severity", Arrays.asList(4, 5))  // 严重和灾难
        .build()
);
```

### User API - 用户管理

```java
// 获取当前用户
List<Map<String, Object>> user = api.user().getCurrentUser();

// 按用户名查询
List<Map<String, Object>> users = api.user().getByUsername("Admin");

// 获取所有用户
List<Map<String, Object>> users = api.user().get(
    ZabbixParams.builder()
        .outputExtend()
        .build()
);
```

## 参数构建器

### 基本用法

```java
// 使用builder模式
ZabbixParams params = ZabbixParams.builder()
    .output(Arrays.asList("hostid", "host"))
    .limit(10)
    .build();

// 或者使用create方法
ZabbixParams params = ZabbixParams.create()
    .param("output", "extend")
    .param("limit", 10);
```

### 常用参数方法

```java
ZabbixParams params = ZabbixParams.builder()
    // 输出字段
    .output(Arrays.asList("hostid", "host", "status"))
    .outputExtend()  // 输出所有字段
    
    // 过滤
    .filter("status", 0)
    .filter("host", "Zabbix server")
    
    // 搜索
    .search("host", "server")
    
    // 排序
    .sortfield("host", "name")
    .sortorder("ASC")  // 或 "DESC"
    
    // 限制
    .limit(100)
    
    // 自定义参数
    .param("selectGroups", "extend")
    .param("selectInterfaces", Arrays.asList("ip", "port"))
    
    .build();
```

## 异常处理

```java
try {
    api.login("Admin", "wrong_password");
} catch (ZabbixAPI.ZabbixAPIException e) {
    System.err.println("错误码: " + e.getCode());
    System.err.println("错误信息: " + e.getMessage());
    System.err.println("详细数据: " + e.getData());
}

// 常见错误码
// -32602: 无效参数
// -32500: 应用程序错误
// -32400: 系统错误
// -32300: 传输错误
```

## 高级特性

### 自定义超时

```java
// 设置30秒超时
ZabbixAPI api = new ZabbixAPI(
    "http://zabbix.example.com/api_jsonrpc.php",
    Duration.ofSeconds(30)
);
```

### 会话管理

```java
// SDK自动管理token
api.login("Admin", "zabbix");  // 获取token
// ... 多次调用API ...
api.logout();  // 销毁token

// 手动获取API版本（无需认证）
String version = api.apiVersion();
```

### 直接访问API对象

```java
// 获取底层ZabbixAPI对象进行更多控制
ZabbixAPI api = service.getZabbixAPI();

// 调用任意API方法
api.call("custom.method", params, responseType);
```

## 在Spring Boot中使用

### 配置服务（推荐方式）

```java
@Service
public class ZabbixService {
    
    private final ZabbixAPI api;
    
    // 方式1: 使用用户名密码（推荐）
    public ZabbixService(
        @Value("${zabbix.api.url}") String url,
        @Value("${zabbix.api.username}") String username,
        @Value("${zabbix.api.password}") String password
    ) throws ZabbixAPI.ZabbixAPIException {
        this.api = new ZabbixAPI(url);
        this.api.login(username, password);
        // SDK会自动管理token，token失效时自动重新登录
    }
    
    // 方式2: 使用已有token
    public ZabbixService(
        @Value("${zabbix.api.url}") String url,
        @Value("${zabbix.api.token}") String token
    ) {
        this.api = new ZabbixAPI(url);
        this.api.withToken(token);
        // 注意：token失效后会抛出异常（除非设置tokenProvider）
    }
    
    // 方式3: 使用token + 自定义刷新（高级用法）
    public ZabbixService(
        @Value("${zabbix.api.url}") String url,
        @Value("${zabbix.api.token}") String token,
        TokenRefreshService refreshService
    ) {
        this.api = new ZabbixAPI(url);
        this.api.withToken(token, () -> {
            // 自定义刷新逻辑（从外部服务获取）
            return refreshService.getNewToken();
        });
    }
    
    @PreDestroy
    public void cleanup() {
        try {
            api.logout();
        } catch (Exception e) {
            log.warn("登出失败", e);
        }
    }
    
    // 业务方法：完全不用担心token过期
    public boolean closeEvents(List<String> eventIds, String operator) {
        try {
            api.event().close(eventIds, "操作人: " + operator);
            return true;
        } catch (ZabbixAPI.ZabbixAPIException e) {
            log.error("关闭事件失败", e);
            return false;
        }
    }
    
    // 获取当前token（用于调试或导出）
    public String getCurrentToken() {
        return api.getAuthToken();
    }
}
```

## 性能优化建议

### 1. 批量操作

```java
// ❌ 不好：多次调用
for (String eventId : eventIds) {
    api.event().close(Arrays.asList(eventId), "关闭");
}

// ✅ 好：批量调用
api.event().close(eventIds, "批量关闭");
```

### 2. 选择性输出

```java
// ❌ 不好：返回所有字段
api.host().get(ZabbixParams.builder().outputExtend().build());

// ✅ 好：只返回需要的字段
api.host().get(
    ZabbixParams.builder()
        .output(Arrays.asList("hostid", "host"))
        .build()
);
```

### 3. 合理使用limit

```java
// 大数据量查询时使用limit
ZabbixParams params = ZabbixParams.builder()
    .output(Arrays.asList("hostid", "host"))
    .limit(1000)
    .build();
```

## 完整示例

### 批量关闭Kafka事件

```java
@Service
@RequiredArgsConstructor
public class EventCloser {
    
    private final ZabbixAPI zabbixAPI;
    
    @Retry(name = "zabbixApi")
    @RateLimiter(name = "zabbixApi")
    public boolean closeEvents(List<String> eventIds, String operator) {
        try {
            // 关闭事件
            Map<String, Object> result = zabbixAPI.event().acknowledge(
                eventIds,
                ZabbixParams.builder()
                    .param("action", 1)
                    .param("message", String.format("批量关闭 - 操作人: %s", operator))
                    .build()
            );
            
            // 验证结果
            @SuppressWarnings("unchecked")
            List<String> closedIds = (List<String>) result.get("eventids");
            
            return closedIds != null && closedIds.size() == eventIds.size();
            
        } catch (ZabbixAPI.ZabbixAPIException e) {
            log.error("关闭事件失败", e);
            throw new RuntimeException(e);
        }
    }
}
```

## API参考

### Action类型（event.acknowledge）

| 值 | 含义 |
|---|---|
| 1 | 关闭事件 |
| 2 | 确认事件 |
| 4 | 添加消息 |
| 8 | 改变严重程度 |
| 16 | 取消确认 |

组合使用：`action = 1 | 4`（关闭并添加消息）

### 严重程度

| 值 | 级别 |
|---|---|
| 0 | 未分类 |
| 1 | 信息 |
| 2 | 警告 |
| 3 | 一般严重 |
| 4 | 严重 |
| 5 | 灾难 |

### 主机状态

| 值 | 状态 |
|---|---|
| 0 | 已启用 |
| 1 | 已禁用 |

## 常见问题

**Q: 如何处理会话过期？**

A: **不需要处理！** SDK基于实际API响应实时检测token失效并自动刷新：

```java
// 使用用户名密码登录（推荐）
api.login("Admin", "zabbix");

// 长时间运行，SDK会在检测到token失效时自动刷新
for (int i = 0; i < 10000; i++) {
    api.host().get();  // 自动处理
    Thread.sleep(60000);
}
```

**Q: 可以直接使用已有的token吗？**

A: 可以！支持三种方式：

```java
// 1. 仅使用token（失效后会报错）
api.withToken("your_token");

// 2. token + 自定义刷新逻辑（推荐）
api.withToken("your_token", () -> getNewTokenFromSomewhere());

// 3. 先用token，失效后改用用户名密码
try {
    api.withToken("token").host().get();
} catch (ZabbixAPIException e) {
    api.login("Admin", "zabbix");
}
```

**Q: 如何获取当前的token？**

A: 使用 `getAuthToken()` 方法：

```java
api.login("Admin", "zabbix");
String token = api.getAuthToken();
System.out.println("Current token: " + token);
```

**Q: token检测是基于什么原理？**

A: SDK不依赖固定的超时时间，而是通过分析Zabbix API的实际响应来判断：

- 检测错误码：-32602, -32500, -32600
- 检测错误消息：包含 "session", "not authorized" 等关键词
- 只在真正失效时才刷新，避免不必要的重新登录

**Q: 并发调用时会重复刷新token吗？**

A: 不会。SDK的token刷新是线程安全的，并发场景下只有一个线程会执行刷新，其他线程会等待。

**Q: 如何调用SDK未封装的API方法？**

A: 使用底层的`call`方法：

```java
api.call("custom.method", params, new TypeReference<ZabbixResponse<T>>() {});
```

**Q: 支持哪些Zabbix版本？**

A: 支持Zabbix 5.0.x, 6.0.x, 7.0.x,

## 许可证

MIT License
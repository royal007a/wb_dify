# Workflow HTTP地址与charset确定性

独立review发现前导零IPv4在JDK与浏览器语义不同，宽松数字判定还可能落到系统DNS。先校验原始URI host，再校验OkHttp host；IPv4严格四段十进制，无前导零，每段<=255，直接从字节构造地址；IPv6拒绝zone与mapped，以只含数字地址字符的语法进入解析。DNS名仍走一次受控解析，literal完全不调用resolver。

响应只接受单个Content-Type；charset参数不得重复；用Charset解析UTF-8别名，正文仍严格UTF-8且32KiB。增加地址奇异写法、直接policy调用、连接地址同一性、响应头负例；不访问云metadata。保留正常literal本机GET正向对照。

窄测先红后绿（如发现测试/代码错误保留原因），提交代码再跑harness/backend/runtime完整门禁。不修改132。本片无数据库变更、无新依赖，回滚为代码回退。002通过证据保持原样。

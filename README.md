# 并发文件下载助手

- 项目基于 JDK15，JavaFX15 进行开发
- 支持http、ftp以及torrent下载（实际上torrent是跟ttorrent学的，还没测试成功，国内种子环境太差了，不知道是我的问题还是种子的问题，~~以后有机会一定填坑~~）
- 操作系统：Windows10
- 若要运行程序，需要安装JDK15以及JavaFX15，并将JavaFX15设为全局依赖库，同时在依赖中加入libs中的jar包，以Launch作为程序入口点，则可运行
- 对于输入文档的格式：每行一个链接，以回车分割
- 对于Torrent和Magnet，支持http、announcelist，不支持DHT、udp

也糊了个ui，大概这样

另外，那个自动生成的框是假的，没有做相应功能，感觉没啥必要

![image-20220211154338058](https://picgo-111.oss-cn-beijing.aliyuncs.com/img/image-20220211154338058.png)

# 内网穿透（手机不在家也能用）

局域网方案要求手机和电脑在同一 WiFi。要在外网使用，有三种方式：

## 方案一：frp + 云服务器（推荐，最可控）

1. 准备一台有公网 IP 的云服务器（阿里云/腾讯云轻量即可）。
2. 服务器上运行 frps（服务端），`frps.toml` 最小配置：

   ```toml
   bindPort = 7000
   auth.token = "改成强随机串"
   ```

3. 本机 `tunnel/` 目录：
   - 下载 frp Windows 版，把 `frpc.exe` 放进 `tunnel/`；
   - 复制 `frpc.example.toml` 为 `frpc.toml`，填入 VPS IP 与 token；
   - 双击 `start_tunnel.bat`。
4. 手机 App 服务器地址填 `http://VPS公网IP:8000`（记得在云服务器安全组放行 8000 端口）。

**安全提醒**：公网暴露前务必改掉 admin 默认密码和弱用户密码，建议在 VPS 上用
nginx + HTTPS 反代后再对外。

## 方案二：Tailscale / ZeroTier 组网（无需公网 IP，最简单）

1. 电脑和手机都安装 Tailscale（或 ZeroTier），登录同一账号（同一网络）。
2. 手机 App 服务器地址填电脑的虚拟 IP，例如 `http://100.x.x.x:8000`。

无需端口暴露，流量端到端加密，国内可用性视运营商而定。

## 方案三：花生壳等商用内网穿透

注册后映射本机 8000 端口即可，免费版有带宽/域名限制，按需选择。

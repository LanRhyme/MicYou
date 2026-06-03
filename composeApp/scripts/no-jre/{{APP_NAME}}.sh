#!/bin/bash
# {{APP_NAME}} 启动脚本
# 此脚本自动检测系统 Java 环境并启动应用

APP_NAME="{{APP_NAME}}"
APP_VERSION="{{APP_VERSION}}"
MAIN_CLASS="{{MAIN_CLASS}}"
APP_HOME="$(cd "$(dirname "$0")" && pwd)"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# 检测 Java 环境
detect_java() {
    local java_cmd=""
    
    # 首先检查 JAVA_HOME
    if [ -n "$JAVA_HOME" ]; then
        if [ -x "$JAVA_HOME/bin/java" ]; then
            java_cmd="$JAVA_HOME/bin/java"
        fi
    fi
    
    # 然后检查 PATH 中的 java
    if [ -z "$java_cmd" ]; then
        if command -v java &> /dev/null; then
            java_cmd="$(command -v java)"
        fi
    fi
    
    echo "$java_cmd"
}

# 获取 Java 版本
get_java_version() {
    local java_cmd="$1"
    if [ -n "$java_cmd" ]; then
        "$java_cmd" -version 2>&1 | grep -oE '"[0-9]+[.0-9]*"' | head -1 | tr -d '"' | cut -d'.' -f1
    fi
}

# 检查 Java 兼容性
check_java_compatibility() {
    local version="$1"
    if [ -n "$version" ] && [ "$version" -ge 17 ]; then
        return 0
    else
        return 1
    fi
}

# 显示 Java 未找到错误
show_java_not_found_error() {
    echo -e "${RED}[错误] 未找到 Java 运行时环境。${NC}"
    echo ""
    echo -e "${YELLOW}请安装 Java 17 或更高版本，并确保以下之一：${NC}"
    echo "  1. JAVA_HOME 环境变量已设置"
    echo "  2. java 命令已在 PATH 中"
    echo ""
    echo -e "${CYAN}您可以从以下地址下载 Java：${NC}"
    echo "  - Eclipse Temurin: https://adoptium.net/"
    echo "  - Oracle JDK: https://www.oracle.com/java/technologies/downloads/"
    echo ""
    
    # macOS 特殊提示
    if [[ "$OSTYPE" == "darwin"* ]]; then
        echo -e "${CYAN}macOS 用户也可以使用 Homebrew 安装：${NC}"
        echo "  brew install --cask temurin"
    fi
    
    # Linux 特殊提示
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        echo -e "${CYAN}Linux 用户也可以使用包管理器安装：${NC}"
        echo "  Ubuntu/Debian: sudo apt install openjdk-17-jre"
        echo "  Fedora: sudo dnf install java-17-openjdk"
        echo "  Arch: sudo pacman -S jre17-openjdk"
    fi
    
    exit 1
}

# 主程序
echo -e "${CYAN}正在检查 Java 环境...${NC}"

JAVA_CMD=$(detect_java)

if [ -z "$JAVA_CMD" ]; then
    show_java_not_found_error
fi

echo -e "${GREEN}使用 Java: $JAVA_CMD${NC}"

JAVA_VERSION=$(get_java_version "$JAVA_CMD")

if ! check_java_compatibility "$JAVA_VERSION"; then
    echo -e "${YELLOW}[警告] 检测到 Java 版本 ${JAVA_VERSION} 可能不兼容。建议使用 Java 17 或更高版本。${NC}"
fi

# 构建类路径
CLASSPATH="$APP_HOME/lib/*"

if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    ALSA_CONFIG="$APP_HOME/resources/alsa/micyou-pipewire.conf"
    if [ -f "$ALSA_CONFIG" ]; then
        export ALSA_CONFIG_PATH="$ALSA_CONFIG"
    fi
fi

# 启动应用
echo -e "${CYAN}启动 $APP_NAME $APP_VERSION...${NC}"
exec "$JAVA_CMD" -cp "$CLASSPATH" "$MAIN_CLASS" "$@"

#!/usr/bin/env bash
#
# Build a release APK signed with your own key.
#
# The first run generates release.jks and keystore.properties (both are
# git ignored). Later runs only rebuild.
#
# Non interactive use:
#   STORE_PASSWORD=yourpassword ./scripts/make-release.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."

KEYSTORE="${KEYSTORE:-release.jks}"
ALIAS="${ALIAS:-sockstun}"
PROPS="keystore.properties"
VALIDITY="${VALIDITY:-10000}"
DNAME="${DNAME:-CN=SocksTun, OU=Personal, O=Personal, C=CN}"

if [ ! -f "$PROPS" ]; then
	if [ ! -f "$KEYSTORE" ]; then
		echo "==> 生成签名密钥: $KEYSTORE"
		if [ -n "${STORE_PASSWORD:-}" ]; then
			PW="$STORE_PASSWORD"
			echo "    使用环境变量 STORE_PASSWORD"
		else
			read -r -s -p "    keystore 密码: " PW; echo
			read -r -s -p "    再输一次:      " PW2; echo
			if [ "$PW" != "$PW2" ]; then
				echo "两次输入不一致，退出"
				exit 1
			fi
		fi
		keytool -genkeypair -v \
			-keystore "$KEYSTORE" \
			-alias "$ALIAS" \
			-keyalg RSA -keysize 2048 \
			-validity "$VALIDITY" \
			-storetype PKCS12 \
			-storepass "$PW" -keypass "$PW" \
			-dname "$DNAME"
		echo "    密钥已生成: $KEYSTORE"
	else
		PW="${STORE_PASSWORD:-}"
		if [ -z "$PW" ]; then
			read -r -s -p "    $KEYSTORE 的密码: " PW; echo
		fi
	fi

	cat > "$PROPS" <<EOF
storeFile=$KEYSTORE
keyAlias=$ALIAS
storePassword=$PW
keyPassword=$PW
EOF
	chmod 600 "$PROPS"
	echo "==> 已写入 $PROPS （已在 .gitignore 中，不会被提交）"
else
	echo "==> 使用已有的 $PROPS"
fi

echo "==> 构建 release APK"
./gradlew assembleRelease

APK="$(ls -1 app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
if [ -z "$APK" ]; then
	echo "没有找到 APK"
	exit 1
fi

echo
echo "==> 产物"
ls -lh "$APK"
echo
shasum -a 256 "$APK"
echo
echo "==> 签名信息"
APKSIGNER="${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools/34.0.0/apksigner"
if [ -x "$APKSIGNER" ]; then
	"$APKSIGNER" verify --print-certs "$APK" | head -6
else
	echo "    (找不到 apksigner，跳过)"
fi

echo
echo "安装到设备:"
echo "    adb install -r $APK"

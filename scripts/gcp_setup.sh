#!/usr/bin/env bash
# =====================================================================
#  One-shot provisioning for a fresh Ubuntu 22.04 LTS Compute Engine VM.
#
#  Installs and wires up the whole portal: MySQL, Apache + mod_cgid for
#  the CGI half, Tomcat 9 for the servlet half. Both halves end up on
#  one machine talking to one database, which is what keeps the
#  benchmark honest -- see REPORT.md.
#
#  Usage, from the repository root:
#      sudo bash scripts/gcp_setup.sh
#
#  Safe to re-run: every step either creates something that does not
#  exist yet, or replaces it outright.
#
#  Ubuntu 22.04 specifically, because it is the last release whose main
#  archive carries BOTH real MySQL 8 and Tomcat 9. Debian 12 and Ubuntu
#  24.04 ship MariaDB and Tomcat 10 instead; Tomcat 10 renamed the
#  servlet API from javax.* to jakarta.*, so this application will not
#  even load on it.
# =====================================================================
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "[setup] run me with sudo: sudo bash scripts/gcp_setup.sh" >&2
  exit 1
fi

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT"

DB_NAME="badgeportal"
DB_USER="badgeuser"
UPLOADS="/var/lib/badgeportal-uploads"
WEBAPPS="/var/lib/tomcat9/webapps"
CGIBIN="/usr/lib/cgi-bin"
PROPS="$PROJECT/config/badgeportal.properties"

echo "[setup] project root: $PROJECT"

# --- 1. packages -----------------------------------------------------
echo "[setup] installing packages (this is the slow part)..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq \
  apache2 mysql-server tomcat9 openjdk-17-jdk python3-pip >/dev/null

# The CGI script imports mysql.connector. Apache runs it as www-data
# with a bare environment, so the driver has to be installed
# system-wide -- a --user install would be invisible to it. This is the
# Linux counterpart of vendoring the driver into C:\xampp\cgi-bin\pylib
# on the Windows setup.
pip3 install --quiet mysql-connector-python

a2enmod cgid >/dev/null
echo "[setup] packages ok"

# --- 2. database -----------------------------------------------------
# Generated rather than prompted, so no password ever sits in your
# shell history or in this file.
if [[ -f "$PROPS" ]] && grep -q '^db.password=' "$PROPS"; then
  DB_PASS="$(grep '^db.password=' "$PROPS" | head -1 | cut -d= -f2-)"
  echo "[setup] reusing the database password already in config/"
else
  DB_PASS="$(openssl rand -base64 18 | tr -d '/+=')"
fi

echo "[setup] creating database and user..."
mysql <<SQL
CREATE DATABASE IF NOT EXISTS ${DB_NAME}
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost'
  IDENTIFIED BY '${DB_PASS}';
ALTER USER '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON ${DB_NAME}.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
SQL

echo "[setup] loading schema, seed and migration..."
mysql "$DB_NAME" < sql/01_schema.sql
mysql "$DB_NAME" < sql/02_seed.sql
mysql "$DB_NAME" < sql/03_accounts_and_claims.sql
echo "[setup] database ok"

# --- 3. configuration ------------------------------------------------
# badgeportal.properties is gitignored, so a fresh clone has none and
# one has to be written here.
#
# The badge secret is generated fresh. That is fine on a new machine:
# no badge in sql/ carries a precomputed code -- badges are minted at
# runtime by the servlet -- so there is nothing already signed with the
# old secret that a new one could invalidate.
if [[ -f "$PROPS" ]] && grep -q '^badge.secret=' "$PROPS"; then
  SECRET="$(grep '^badge.secret=' "$PROPS" | head -1 | cut -d= -f2-)"
else
  SECRET="$(openssl rand -hex 32)"
fi

mkdir -p "$PROJECT/config"
cat > "$PROPS" <<EOF
# Written by scripts/gcp_setup.sh -- shared by BOTH implementations.
db.host=localhost
db.port=3306
db.name=${DB_NAME}
db.user=${DB_USER}
db.password=${DB_PASS}
db.pool.size=16

badge.secret=${SECRET}

upload.dir=${UPLOADS}
upload.max.bytes=5242880
EOF
chmod 600 "$PROPS"

# Outside the webapp on purpose: anything under webapps/ is served as a
# static file by Tomcat, so evidence stored there would be readable by
# anyone who guessed a filename.
mkdir -p "$UPLOADS"
chown tomcat:tomcat "$UPLOADS"
chmod 750 "$UPLOADS"
echo "[setup] config written to config/badgeportal.properties (mode 600)"

# --- 4. build the servlet --------------------------------------------
echo "[setup] compiling servlet sources..."
OUT="$PROJECT/build/badgeportal"
rm -rf "$PROJECT/build"
mkdir -p "$OUT/WEB-INF/classes" "$OUT/WEB-INF/lib"

SERVLET_API="/usr/share/tomcat9/lib/servlet-api.jar"
[[ -f "$SERVLET_API" ]] || { echo "[setup] servlet-api.jar missing" >&2; exit 1; }

# --release 8 matches scripts/build.bat. Not required here -- Tomcat 9
# runs on Java 17 -- but keeping one target means the bytecode you test
# on Windows is the bytecode you run here.
find servlet/src -name '*.java' > build/sources.txt
javac -encoding UTF-8 -Xlint:-options --release 8 \
  -cp "$SERVLET_API" -d "$OUT/WEB-INF/classes" @build/sources.txt

cp -r servlet/web/. "$OUT/"
cp lib/mysql-connector-j-8.4.0.jar "$OUT/WEB-INF/lib/"
cp "$PROPS" "$OUT/WEB-INF/badgeportal.properties"

rm -rf "$WEBAPPS/badgeportal"
cp -r "$OUT" "$WEBAPPS/badgeportal"
chown -R tomcat:tomcat "$WEBAPPS/badgeportal"
chmod 640 "$WEBAPPS/badgeportal/WEB-INF/badgeportal.properties"
echo "[setup] servlet deployed to $WEBAPPS/badgeportal"

# --- 5. deploy the CGI half ------------------------------------------
echo "[setup] deploying CGI scripts..."
cp cgi/verify.py cgi/badgecode.py cgi/dbconfig.py "$CGIBIN/"

# The shebang in the repo points at the Windows interpreter. Apache
# execs the script directly, so it has to name a real path on this box.
sed -i '1s|.*|#!/usr/bin/python3|' "$CGIBIN/verify.py"
sed -i 's/\r$//' "$CGIBIN"/verify.py "$CGIBIN"/badgecode.py "$CGIBIN"/dbconfig.py

# dbconfig.py looks for badgeportal.properties beside itself before it
# falls back to ../config/, which does not exist under /usr/lib/cgi-bin.
cp "$PROPS" "$CGIBIN/badgeportal.properties"
chown root:www-data "$CGIBIN/badgeportal.properties"
chmod 640 "$CGIBIN/badgeportal.properties"
chmod 755 "$CGIBIN/verify.py"
echo "[setup] CGI deployed to $CGIBIN"

# --- 6. restart ------------------------------------------------------
systemctl restart apache2
systemctl restart tomcat9
sleep 6

IP="$(curl -s -H 'Metadata-Flavor: Google' \
  http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip \
  || echo 'YOUR_VM_IP')"

echo
echo "====================================================================="
echo " done"
echo
echo "   servlet   http://${IP}:8080/badgeportal/index.html"
echo "   CGI       http://${IP}/cgi-bin/verify.py?code=TEST"
echo
echo " If the pages do not load, open the firewall (from Cloud Shell):"
echo "   gcloud compute firewall-rules create badgeportal-http \\"
echo "     --allow tcp:80,tcp:8080 --target-tags=http-server"
echo
echo " CHANGE THE DEMO PASSWORDS before leaving this box public:"
echo "   admin@college.edu is still Admin@123"
echo "====================================================================="

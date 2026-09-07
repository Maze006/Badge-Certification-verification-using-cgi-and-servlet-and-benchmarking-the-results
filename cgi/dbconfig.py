"""
Reads badgeportal.properties -- the SAME configuration file the servlet
reads through com.badgeportal.Config.

One file, two runtimes. If the CGI script and the servlet pointed at
different databases or used different signing secrets, the benchmark
would be comparing two different pieces of work rather than two ways of
running the same one.

Resolution order:
  1. the BADGEPORTAL_CONFIG environment variable, if set
  2. badgeportal.properties sitting next to this file (this is what the
     deploy script sets up, since C:/xampp/cgi-bin is outside the repo)
  3. ../config/badgeportal.properties, for running straight from a checkout
  4. built-in defaults
"""

import os

# Fallbacks used only when no properties file is found. The signing
# secret here is a deliberate placeholder, never the real key: the real
# one lives in config/badgeportal.properties, which is gitignored,
# because anyone holding it can forge a verification code that passes
# the tamper-evident check.
#
# It must stay identical to the fallback in com.badgeportal.Config, so
# that a fresh checkout with no properties file still has the two
# implementations agreeing with each other.
_DEFAULTS = {
    "db.host": "localhost",
    "db.port": "3306",
    "db.name": "badgeportal",
    "db.user": "badgeuser",
    "db.password": "badgepass123",
    "badge.secret": "CHANGE_ME_TO_A_LONG_RANDOM_STRING",
}

_HERE = os.path.dirname(os.path.abspath(__file__))


def _candidate_paths():
    env = os.environ.get("BADGEPORTAL_CONFIG", "").strip()
    if env:
        yield env
    yield os.path.join(_HERE, "badgeportal.properties")
    yield os.path.join(_HERE, "..", "config", "badgeportal.properties")


def _parse(path):
    """A minimal java.util.Properties reader: key=value, # comments."""
    values = {}
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line[0] in "#!":
                continue
            if "=" not in line:
                continue
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()
    return values


def load():
    """Returns (settings_dict, source_description)."""
    for path in _candidate_paths():
        try:
            if os.path.isfile(path):
                merged = dict(_DEFAULTS)
                merged.update(_parse(path))
                return merged, os.path.abspath(path)
        except (OSError, UnicodeDecodeError):
            continue
    return dict(_DEFAULTS), "built-in defaults"


SETTINGS, SOURCE = load()


def db_params():
    """Keyword arguments for mysql.connector.connect()."""
    return {
        "host": SETTINGS["db.host"],
        "port": int(SETTINGS["db.port"]),
        "database": SETTINGS["db.name"],
        "user": SETTINGS["db.user"],
        "password": SETTINGS["db.password"],
        # Matches the servlet: no SSL to localhost, autocommit on.
        "ssl_disabled": True,
        "autocommit": True,
        "use_pure": True,
    }


def secret():
    return SETTINGS["badge.secret"]

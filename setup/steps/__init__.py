"""Installation steps in execution order.

The order is not arbitrary and should not be changed casually: checks precede
all modifications; the service user exists before directories are assigned;
configuration learns the database password only after database creation; and
verification comes last because it is meaningful only after service startup.
"""

from steps.app_user import AppUserStep
from steps.database import DatabaseStep
from steps.env_config import EnvConfigStep
from steps.exposure import ExposureStep
from steps.firewall import FirewallStep
from steps.install_app import InstallAppStep
from steps.packages import JavaStep
from steps.proxy_setup import ProxySetupStep
from steps.system_check import SystemCheckStep
from steps.systemd_service import SystemdStep
from steps.verify import VerifyStep


def build_steps():
    return [
        SystemCheckStep(),
        JavaStep(),
        DatabaseStep(),
        AppUserStep(),
        InstallAppStep(),
        EnvConfigStep(),
        SystemdStep(),
        ProxySetupStep(),
        ExposureStep(),
        FirewallStep(),
        VerifyStep(),
    ]

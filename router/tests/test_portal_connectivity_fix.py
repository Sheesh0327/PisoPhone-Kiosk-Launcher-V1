"""Test verification for the three captive portal connectivity fixes:
1. openNDS users_to_router allows UDP 53, TCP 53, UDP 67, and TCP $PORTAL_PORT (while blocking SSH 22 & LuCI 443).
2. page.html wake() redirects to cont / probe URL if available.
3. 100.64.0.0/10 (CGNAT) removed from guest_noprivate and phone_noprivate firewall reject lists.
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

def test_fix1_opennds_rules():
    in_file = os.path.join(ROOT, "setup", "piso-setup.sh.in")
    content = open(in_file).read()
    
    assert "delete opennds.@opennds[0].users_to_router" in content
    assert "add_list opennds.@opennds[0].users_to_router='allow tcp port 53'" in content
    assert "add_list opennds.@opennds[0].users_to_router='allow udp port 53'" in content
    assert "add_list opennds.@opennds[0].users_to_router='allow udp port 67'" in content
    assert "add_list opennds.@opennds[0].users_to_router='allow tcp port $PORTAL_PORT'" in content

    # Management ports must remain blocked
    assert "add_list opennds.@opennds[0].users_to_router='allow tcp port 22'" not in content
    assert "add_list opennds.@opennds[0].users_to_router='allow tcp port 443'" not in content

    for name in ["piso-setup.sh", "piso-setup-aarch64.sh", "piso-setup-armv7.sh", "piso-setup-mips.sh", "piso-setup-x86_64.sh"]:
        path = os.path.join(ROOT, "setup", name)
        c = open(path).read()
        assert "add_list opennds.@opennds[0].users_to_router='allow udp port 53'" in c
        assert "add_list opennds.@opennds[0].users_to_router='allow tcp port 53'" in c
        assert "add_list opennds.@opennds[0].users_to_router='allow udp port 67'" in c
        assert "add_list opennds.@opennds[0].users_to_router='allow tcp port $PORTAL_PORT'" in c


def test_fix2_wake_redirect():
    html_path = os.path.join(ROOT, "tools", "pisoportal", "src", "page.html")
    content = open(html_path, encoding="utf-8").read()
    assert 'function wake(){wk=null;if(woke)return;if(cont&&cont.indexOf("http")===0){location.replace(cont)}else{var q=clean();location.replace(location.pathname+q+(q?"&":"?")+"w=1")}}' in content
    assert len(content) < 28 * 1024


def test_fix3_cgnat_rules():
    in_file = os.path.join(ROOT, "setup", "piso-setup.sh.in")
    content = open(in_file).read()
    
    assert "add_list firewall.guest_noprivate.dest_ip='100.64.0.0/10'" not in content
    assert "add_list firewall.phone_noprivate.dest_ip='100.64.0.0/10'" not in content
    
    for r in ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"]:
        assert f"add_list firewall.guest_noprivate.dest_ip='{r}'" in content
        assert f"add_list firewall.phone_noprivate.dest_ip='{r}'" in content

    for name in ["piso-setup.sh", "piso-setup-aarch64.sh", "piso-setup-armv7.sh", "piso-setup-mips.sh", "piso-setup-x86_64.sh"]:
        path = os.path.join(ROOT, "setup", name)
        c = open(path).read()
        assert "add_list firewall.guest_noprivate.dest_ip='100.64.0.0/10'" not in c
        assert "add_list firewall.phone_noprivate.dest_ip='100.64.0.0/10'" not in c


if __name__ == "__main__":
    test_fix1_opennds_rules()
    test_fix2_wake_redirect()
    test_fix3_cgnat_rules()
    print("ALL CONNECTIVITY FIXES VERIFIED SUCCESSFULLY")

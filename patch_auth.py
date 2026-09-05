import os

def patch_file(filepath):
    with open(filepath, "r") as f:
        content = f.read()

    target = "let ownerToken = localStorage.getItem('piso_google_credential');"
    repl = """let ownerToken = null;
        const storedUser = localStorage.getItem('piso_google_user');
        if (storedUser) {
            try {
                ownerToken = JSON.parse(storedUser).token;
            } catch (e) {}
        }"""
    
    if target in content:
        content = content.replace(target, repl)
        with open(filepath, "w") as f:
            f.write(content)
        print(f"Patched {filepath}")
    else:
        print(f"Target not found in {filepath}")

patch_file("website/activate.html")
patch_file("website/purchase.html")

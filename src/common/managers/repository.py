import json
import logging
import subprocess
from pathlib import Path


class RepositoryManager:
    def __init__(self, repo_url: str):
        self.repo_url = repo_url

    def clone_and_build(self, repo_script: str):
        subprocess.run([repo_script, self.repo_url], check=True)

    def get_commit_hash(self, repo_path: str) -> str:
        
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            check=True
        )
        return result.stdout.strip()

    def get_files(self, extension: str, folder: str):
        folder = Path(folder)
        logging.info(f"Searching for files with extension: {extension} in {folder}")
        logging.info(f"Files found: {list(folder.rglob(f'*.{extension}'))}")
        ext = extension.lstrip(".")
        for filename in folder.rglob(f"*.{ext}"):
            logging.info(f"Found file: {filename}")
            yield filename

    def generate_cfg_from_file(self, filename: str, cfg_build_script: str):
        try:
            result = subprocess.run(
                [cfg_build_script, filename],
                capture_output=True,
                text=True,
                check=True
            )
            return json.loads(result.stdout)
        except subprocess.CalledProcessError as e:
            raise RuntimeError(
                f"CFG build failed for {filename}:\n{e.stderr}"
            ) from e


    def rm(self, repo_dir):
        subprocess.run(["rm", "-rf", repo_dir], check=True)

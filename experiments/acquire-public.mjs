/** Korean public-data acquisition. Python handles streaming ZIP validation and multipart POST. */
import {spawnSync} from 'node:child_process';
import {existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const here=path.dirname(fileURLToPath(import.meta.url));
const embedded=path.join(here,'.cache','venv','python.exe');
const python=process.env.PUBLIC_PYTHON || (existsSync(embedded)?embedded:'python');
const result=spawnSync(python,[path.join(here,'acquire_public.py'),...process.argv.slice(2)],{stdio:'inherit'});
if(result.error)throw result.error;
process.exitCode=result.status ?? 1;

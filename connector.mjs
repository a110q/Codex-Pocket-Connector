// CLI only. Node resolves module URLs to their real paths, while argv can
// retain a junction, mapped drive or differently cased spelling on Windows.
// Always start here when invoked; importing the server never starts the CLI.
const args = process.argv.slice(2);
const backend = await import('./connector-server.mjs').catch(error => {
  if (args.includes('--native') && args.includes('--descriptor')) {
    const code = ['ERR_MODULE_NOT_FOUND', 'ERR_UNKNOWN_BUILTIN_MODULE'].includes(error.code) ? error.code : 'UNKNOWN';
    console.error(`POCKET_STARTUP_ERROR:BACKEND_IMPORT:${code}`);
  } else {
    console.error(error.message);
  }
  process.exitCode = 1;
  return null;
});
if (backend) await backend.runConnector(args);

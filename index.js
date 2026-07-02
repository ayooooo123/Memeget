import { registerRootComponent } from 'expo';

import App from './App';
// Side-effect import: defines the background-describe TaskManager task at the
// JS entry so the OS can invoke it even on a headless (no-UI) background launch.
import './src/backgroundTask';

registerRootComponent(App);

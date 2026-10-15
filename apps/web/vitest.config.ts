import {defineConfig} from 'vitest/config';
export default defineConfig({test:{include:['test/unit/**/*.test.ts'],reporters:['default','junit'],outputFile:{junit:'test-results/unit.xml'}},resolve:{alias:{'@':new URL('./src',import.meta.url).pathname}}});

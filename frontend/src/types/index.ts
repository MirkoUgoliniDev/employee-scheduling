/**
 * @file types/index.ts
 * @brief TypeScript types shared between components and the store.
 *
 * @details
 * This file contains only the types used by the Zustand store and by components that do not
 * interact directly with the APIs. For types tied to individual APIs
 * (with payloads, skills, etc.), refer to the corresponding `api/*.ts` files.
 */

// ─── Structure ───────────────────────────────────────────────────────────────

/**
 * @brief Organizational structure (reduced version for the store).
 * @see api/structures.ts for the complete version with API methods.
 */
export interface Structure {
  id: number
  name: string
  address?: string
  phone?: string
}

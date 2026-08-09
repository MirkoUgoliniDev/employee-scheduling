/**
 * @file register.ts
 * @brief Self-registration for the CAPOSALA role via OTP (email → verification → profile).
 *
 * @details Flow consisting of three public calls:
 *          1. requestOtp(email)—sends the code;
 *          2. verifyOtp(email, otp)—verifies it and obtains a one-time token;
 *          3. complete(token, username, password)—creates the account pending approval.
 */

import { api } from './client'

export interface RegisterStatus {
  firstUser: boolean
  /** @brief 'standalone' (desktop, without OTP) or 'server' (email verification via OTP). */
  mode: 'standalone' | 'server'
  /** @brief true if registration requires an OTP code (server mode only). */
  otpRequired: boolean
}

export const registerApi = {
  /** @brief Registration status: first user, mode, and whether OTP is required. */
  status: () => api.get<RegisterStatus>('/auth/register/status'),

  requestOtp: (email: string) =>
    api.post<{ sent: boolean; email: string }>('/auth/register/otp', { email }),

  verifyOtp: (email: string, otp: string) =>
    api.post<{ token: string }>('/auth/register/verify', { email, otp }),

  /**
   * @brief Creates the account.
   * @param token One-time token: required in server mode, ignored in standalone mode.
   */
  complete: (token: string | null, username: string, password: string) =>
    api.post<{ created: boolean; pendingApproval: boolean; admin?: boolean }>('/auth/register/complete', {
      token,
      username,
      password,
    }),
}

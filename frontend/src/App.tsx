/**
 * @file App.tsx
 * @brief Root component — defines application routing.
 *
 * @details
 * Public routes (before login): /login and /register. Everything else is protected:
 * users without a session are redirected to /login. The shared layout (Navbar + Bootstrap
 * container) wraps authenticated pages.
 */

import { lazy, Suspense } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import Navbar from './components/Navbar'
import UpdateNoticeModal from './components/UpdateNoticeModal'
import { AuthProvider, useAuth } from './auth/AuthContext'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'

const ShiftsPage = lazy(() => import('./pages/ShiftsPage'))
const HomePage = lazy(() => import('./pages/HomePage'))
const EmployeesPage = lazy(() => import('./pages/EmployeesPage'))
const SpecialistsPage = lazy(() => import('./pages/SpecialistsPage'))
const LocationsPage = lazy(() => import('./pages/LocationsPage'))
const DatesPage = lazy(() => import('./pages/DatesPage'))
const ReportPage = lazy(() => import('./pages/ReportPage'))
const ConfigPage = lazy(() => import('./pages/ConfigPage'))
const UsersPage = lazy(() => import('./pages/UsersPage'))

/**
 * @brief Decides whether to show the application or the public screens.
 * @details Nothing is shown while `loading`: we do not yet know whether a session exists,
 *          and showing the login would cause a flash for users who are already signed in.
 */
function App() {
  const { loading, isAuthenticated, needsFirstAdmin, isAdmin } = useAuth()

  if (loading) {
    return (
      <div className="d-flex justify-content-center align-items-center" style={{ minHeight: '100vh' }}>
        <div className="spinner-border text-secondary" role="status" />
      </div>
    )
  }

  return (
    <Routes>
      {/* Public pages. With an empty user table (standalone), go straight to
          administrator creation: login does not make sense yet.

          With a valid session both routes redirect to the application: without this,
          after a successful login the URL remains /login, which takes precedence over the "*" route,
          and the user would see the form again despite being authenticated. This also applies to
          users who reload /login or /register while their cookie is still valid. */}
      <Route path="/login" element={
        isAuthenticated ? <Navigate to="/" replace />
          : needsFirstAdmin ? <Navigate to="/register" replace />
          : <LoginPage />} />
      <Route path="/register" element={
        isAuthenticated ? <Navigate to="/" replace /> : <RegisterPage />} />

      {/* Authenticated app: without a session, go to login (or first-admin creation) */}
      <Route
        path="*"
        element={!isAuthenticated ? <Navigate to={needsFirstAdmin ? '/register' : '/login'} replace /> : (
          <>
            <Navbar />
            {/* New-version notice: administrators only, since they are the only users who can
                update. It performs its own check and remains hidden when there is nothing
                to report or when the check is disabled. */}
            {isAdmin && <UpdateNoticeModal />}
            <div className="container-fluid py-3">
              <Suspense fallback={<div className="d-flex justify-content-center py-5"><div className="spinner-border text-secondary" role="status" /></div>}>
              <Routes>
                <Route path="/" element={<HomePage />} />
                <Route path="/shifts" element={<ShiftsPage />} />
                <Route path="/employees" element={<EmployeesPage />} />
                <Route path="/specialists" element={<SpecialistsPage />} />
                <Route path="/locations" element={<LocationsPage />} />
                <Route path="/skills" element={<Navigate to="/config?section=skills" replace />} />
                <Route path="/dates" element={<DatesPage />} />
                <Route path="/report" element={<ReportPage />} />
                <Route path="/structures" element={<Navigate to="/config?section=structures" replace />} />
                <Route path="/labels" element={<Navigate to="/config?section=localizations" replace />} />
                <Route path="/config" element={<ConfigPage />} />
                <Route path="/users" element={<UsersPage />} />
                {/* Old bookmark or manually entered address: without this route,
                    the Navbar and content area would remain completely empty. */}
                <Route path="*" element={<Navigate to="/shifts" replace />} />
              </Routes>
              </Suspense>
            </div>
          </>
        )}
      />
    </Routes>
  )
}

/** @brief Root: the session must exist before any page can request data. */
export default function AppWithAuth() {
  return (
    <AuthProvider>
      <App />
    </AuthProvider>
  )
}

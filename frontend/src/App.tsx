import { createBrowserRouter, Link, Outlet, RouterProvider } from 'react-router'
import { AnalysisPage } from './pages/AnalysisPage'
import { DemoPage } from './pages/DemoPage'
import { HomePage } from './pages/HomePage'

function Layout() {
  return (
    <main className="page">
      <header className="site-header">
        <h1>
          <Link to="/">CodeAtlas</Link>
        </h1>
        <p className="tagline">
          See a repository's structure, where to start reading, and how its code changes, computed from the code
          and its git history.
        </p>
      </header>
      <Outlet />
    </main>
  )
}

const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { path: '/', element: <HomePage /> },
      { path: '/analyses/:id', element: <AnalysisPage /> },
      { path: '/demo/:name', element: <DemoPage /> },
    ],
  },
])

function App() {
  return <RouterProvider router={router} />
}

export default App

import { BackendStatus } from './components/BackendStatus'

function App() {
  return (
    <main className="page">
      <h1>CodeAtlas</h1>
      <p className="tagline">
        Point it at a repository to see its architecture, where to start reading, who owns what, and which
        files change together.
      </p>
      <section className="card">
        <BackendStatus />
      </section>
    </main>
  )
}

export default App

package com.example.socialmediablocker

import android.content.Context
import android.os.Bundle
import android.text.style.UnderlineSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator

class IntroActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intro)

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout)
        val tvSkip = findViewById<TextView>(R.id.tvSkip)

        // Underline skip button
        val skipText = getString(R.string.intro_skip)
        val content = android.text.SpannableString(skipText)
        content.setSpan(UnderlineSpan(), 0, skipText.length, 0)
        tvSkip.text = content

        val introItems = listOf(
            IntroItem(R.drawable.ic_launcher, getString(R.string.intro_title_1), getString(R.string.intro_desc_1)),
            IntroItem(R.drawable.ic_launcher, getString(R.string.intro_title_2), getString(R.string.intro_desc_2))
        )

        viewPager.adapter = IntroAdapter(introItems)

        val btnClose = findViewById<android.widget.ImageButton>(R.id.btnClose)
        btnClose.setOnClickListener {
            // Just close and go to main, but don't save skip flag
            val mainIntent = android.content.Intent(this, MainActivity::class.java)
            mainIntent.putExtra("from_intro", true)
            startActivity(mainIntent)
            finish()
        }

        TabLayoutMediator(tabLayout, viewPager) { _, _ -> }.attach()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // position is 0-indexed. Step 2 is index 1.
                if (position == 1) {
                    tvSkip.visibility = View.VISIBLE
                    tvSkip.isEnabled = true
                    btnClose.visibility = View.VISIBLE
                } else {
                    tvSkip.visibility = View.INVISIBLE
                    tvSkip.isEnabled = false
                    btnClose.visibility = View.GONE
                }
            }
        })

        tvSkip.setOnClickListener {
            // Save flag to skip intro next time
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("skip_intro", true).apply()
            
            val mainIntent = android.content.Intent(this, MainActivity::class.java)
            mainIntent.putExtra("from_intro", true)
            startActivity(mainIntent)
            finish()
        }
    }

    data class IntroItem(val imageRes: Int, val title: String, val desc: String)

    inner class IntroAdapter(private val items: List<IntroItem>) : RecyclerView.Adapter<IntroAdapter.IntroViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IntroViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_intro_page, parent, false)
            return IntroViewHolder(view)
        }

        override fun onBindViewHolder(holder: IntroViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class IntroViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivImage = itemView.findViewById<ImageView>(R.id.ivIntroImage)
            private val tvTitle = itemView.findViewById<TextView>(R.id.tvIntroTitle)
            private val tvDesc = itemView.findViewById<TextView>(R.id.tvIntroDesc)

            fun bind(item: IntroItem) {
                ivImage.setImageResource(item.imageRes)
                tvTitle.text = item.title
                tvDesc.text = item.desc
            }
        }
    }
}
